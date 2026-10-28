/**
 * ============================================
 *  سيرفر مزامنة "حصالة البيت" (House Budget Sync)
 * ============================================
 * وسيط خفيف بين هاتفين يشاركان نفس رمز الربط:
 *  - الهاتف الأول يرفع مصاريفه وقوالبه ومراسلاته وتحديثات الميزانية عبر POST /api/:code/sync
 *  - الهاتف الثاني يسحب الحالة الموحدة عبر نفس الطلب أو GET /api/:code/state
 *  - الدمج بقاعدة "الأحدث يفوز" حسب حقل updatedAt لكل سجل (مصاريف + قوالب + مراسلات + إعدادات)
 *
 * التخزين: ملف JSON خفيف (بدون أي قواعد بيانات خارجية) —
 * مثالي كوسيط مؤقت على خطة Render المجانية.
 *
 * جاهز للنشر الفوري على Render (راجع ملف render.yaml في جذر المستودع).
 */
const express = require('express');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'sync-store.json');
const FILES_DIR = path.join(DATA_DIR, 'files');
const crypto = require('crypto');

// حد أقصى للحماية من نمو غير طبيعي (يكفي لمصاريف بيت لسنوات)
const MAX_EXPENSES_PER_HOUSE = 5000;
const MAX_TEMPLATES_PER_HOUSE = 200;
const MAX_MESSAGES_PER_HOUSE = 500;

// ملفات المراسلات (الإصدار 1.8): رسائل صوتية وصور بين الهاتفين
// الحد الأقصى لكل ملف 6 ميجابايت بعد فك الترميز، و150 ملفاً لكل منزل
// (عند التجاوز تُحذف الأقدم تلقائياً — الرسائل النصية تبقى كاملة)
const MAX_FILE_BYTES = 6 * 1024 * 1024;
const MAX_FILES_PER_HOUSE = 150;
const VALID_MEDIA_TYPES = ['', 'IMAGE', 'AUDIO'];

// رمز الربط: حروف إنجليزية وأرقام فقط، 4 إلى 32 محرفاً
const CODE_RE = /^[A-Za-z0-9]{4,32}$/;

// ---------- المخزن الخفيف (ذاكرة + حفظ مؤجل في ملف JSON) ----------
let store = {};
try {
  store = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  console.log('تم تحميل بيانات المزامنة السابقة من الملف');
} catch (e) {
  store = {};
}

let saveTimer = null;
function persist() {
  if (saveTimer) return; // تجميع عمليات الحفظ المتلاحقة في عملية واحدة
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      fs.writeFileSync(DATA_FILE, JSON.stringify(store));
    } catch (e) {
      console.error('تعذر حفظ البيانات:', e.message);
    }
  }, 200);
}

function getHouse(code) {
  if (!store[code]) store[code] = { expenses: {}, templates: {}, messages: {}, files: {}, settings: null };
  if (!store[code].expenses) store[code].expenses = {};
  // توافق خلفي: بيوت أُنشئت قبل إضافة القوالب
  if (!store[code].templates) store[code].templates = {};
  // توافق خلفي: بيوت أُنشئت قبل إضافة المراسلات (طلبات الشراء + الرسائل النصية)
  if (!store[code].messages) store[code].messages = {};
  // توافق خلفي: بيوت أُنشئت قبل ملفات الوسائط (صوتيات وصور — الإصدار 1.8)
  if (!store[code].files) store[code].files = {};
  return store[code];
}

// مجلد ملفات منزل معين (يُنشأ عند الحاجة)
function houseFilesDir(code) {
  const dir = path.join(FILES_DIR, code);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

// حذف ملف وسائط من القرص ومن سجلات المنزل
function deleteHouseFile(code, house, fileId) {
  if (!fileId || !house.files[fileId]) return;
  delete house.files[fileId];
  try {
    // الاسم على القرص مضمن مع رمز المنزل لتفادي أي تداخل بين المنازل
    fs.unlinkSync(path.join(FILES_DIR, code, fileId.replace(/[^A-Za-z0-9_-]/g, '')));
  } catch (e) {
    // الملف غير موجود على القرص — تجاهل
  }
}

// تقليم ملفات منزل: عند التجاوز نحذف الأقدم إنشاءً (السجلات + القرص)
function pruneHouseFiles(code, house) {
  const fids = Object.keys(house.files);
  if (fids.length <= MAX_FILES_PER_HOUSE) return;
  fids.sort((a, b) => (house.files[a].createdAt || 0) - (house.files[b].createdAt || 0));
  const toRemove = fids.length - MAX_FILES_PER_HOUSE;
  for (let i = 0; i < toRemove; i++) deleteHouseFile(code, house, fids[i]);
}

function isValidCode(code) {
  return typeof code === 'string' && CODE_RE.test(code);
}

// ---------- التطبيق ----------
const app = express();
// حد مرتفع لطلبات رفع ملفات الوسائط (صوت/صورة) بصيغة base64 داخل JSON
app.use(express.json({ limit: '12mb' }));

// السماح بكل المصادر (مفيد للاختبار من المتصفح؛ التطبيق لا يحتاج CORS)
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// صفحة ترحيب بسيطة للتأكد من أن السيرفر يعمل عند فتح رابط Render في المتصفح
app.get('/', (req, res) => {
  res.type('html').send(
    '<!DOCTYPE html><html dir="rtl" lang="ar"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<title>حصالة البيت — سيرفر المزامنة</title>' +
    '<style>body{font-family:system-ui,sans-serif;background:#FAF7F0;color:#1F2937;' +
    'display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}' +
    '.card{background:#fff;padding:40px;border-radius:20px;box-shadow:0 2px 12px rgba(0,0,0,.08);' +
    'text-align:center;max-width:420px}h1{color:#2E7D32;margin:0 0 8px}</style></head>' +
    '<body><div class="card"><h1>حصالة البيت</h1>' +
    '<p>سيرفر المزامنة يعمل بنجاح</p>' +
    '<p style="color:#6B7280;font-size:14px">استخدم هذا الرابط في تطبيق حصالة البيت على الهاتفين،' +
    ' مع نفس رمز الربط في كليهما.</p>' +
    '<p style="color:#6B7280;font-size:12px">فحص الصحة: <code>GET /health</code></p>' +
    '</div></body></html>'
  );
});

// فحص صحة عام
app.get('/health', (req, res) => {
  res.json({
    ok: true,
    service: 'house-budget-sync',
    time: Date.now(),
    uptimeSec: Math.floor(process.uptime()),
    heartbeat: heartbeatState
  });
});

// فحص وجود رمز الربط وصحة السيرفر
app.get('/api/:code/ping', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.json({ ok: false, error: 'رمز الربط غير صالح (حروف إنجليزية وأرقام فقط، 4-32 محرفاً)' });
  }
  res.json({ ok: true, name: 'house-budget-sync' });
});

// جلب الحالة الموحدة الكاملة دون رفع أي شيء
app.get('/api/:code/state', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const house = getHouse(code);
  res.json({
    ok: true,
    expenses: Object.values(house.expenses),
    templates: Object.values(house.templates),
    messages: Object.values(house.messages),
    settings: house.settings,
    serverTime: Date.now()
  });
});

// المزامنة الكاملة: رفع بيانات الهاتف → دمج بالأحدث يفوز → إرجاع الحالة الموحدة
app.post('/api/:code/sync', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }

  const body = req.body || {};
  const incoming = Array.isArray(body.expenses) ? body.expenses : [];
  const house = getHouse(code);

  // دمج المصاريف: سجل واحد لكل معرّف، والنسخة الأحدث تحديثاً تفوز
  for (const raw of incoming) {
    if (!raw || typeof raw.id !== 'string') continue;
    const clean = {
      id: raw.id.slice(0, 64),
      name: String(raw.name || '').slice(0, 120),
      amount: Number(raw.amount) || 0,
      category: String(raw.category || 'OTHER').slice(0, 16),
      createdAt: Number(raw.createdAt) || 0,
      updatedAt: Number(raw.updatedAt) || 0,
      deleted: Boolean(raw.deleted)
    };
    const existing = house.expenses[clean.id];
    if (!existing || clean.updatedAt >= existing.updatedAt) {
      house.expenses[clean.id] = clean;
    }
  }

  // دمج القوالب السريعة: نفس قاعدة الأحدث يفوز (تشمل علامات الحذف الناعم)
  const incomingTemplates = Array.isArray(body.templates) ? body.templates : [];
  for (const raw of incomingTemplates) {
    if (!raw || typeof raw.id !== 'string') continue;
    const clean = {
      id: raw.id.slice(0, 64),
      name: String(raw.name || '').slice(0, 120),
      amount: Number(raw.amount) || 0,
      category: String(raw.category || 'OTHER').slice(0, 16),
      sortOrder: Number(raw.sortOrder) || 0,
      updatedAt: Number(raw.updatedAt) || 0,
      deleted: Boolean(raw.deleted)
    };
    const existing = house.templates[clean.id];
    if (!existing || clean.updatedAt >= existing.updatedAt) {
      house.templates[clean.id] = clean;
    }
  }

  // دمج المراسلات (طلبات الشراء + الرسائل النصية): نفس قاعدة الأحدث يفوز
  const incomingMessages = mergeMessages(house, body.messages);

  // حد أقصى للحماية: عند التجاوز نحتفظ بالسجلات الأحدث تحديثاً
  const ids = Object.keys(house.expenses);
  if (ids.length > MAX_EXPENSES_PER_HOUSE) {
    ids.sort((a, b) => (house.expenses[a].updatedAt || 0) - (house.expenses[b].updatedAt || 0));
    const toRemove = ids.length - MAX_EXPENSES_PER_HOUSE;
    for (let i = 0; i < toRemove; i++) delete house.expenses[ids[i]];
  }

  // حد أقصى للحماية: عند التجاوز نحتفظ بالقوالب الأحدث تحديثاً
  const tids = Object.keys(house.templates);
  if (tids.length > MAX_TEMPLATES_PER_HOUSE) {
    tids.sort((a, b) => (house.templates[a].updatedAt || 0) - (house.templates[b].updatedAt || 0));
    const toRemoveT = tids.length - MAX_TEMPLATES_PER_HOUSE;
    for (let i = 0; i < toRemoveT; i++) delete house.templates[tids[i]];
  }

  // دمج الإعدادات المشتركة: الأحدث زمنياً يفوز
  const incomingSettings = body.settings;
  if (incomingSettings && typeof incomingSettings === 'object' && typeof incomingSettings.updatedAt === 'number') {
    if (!house.settings || incomingSettings.updatedAt >= house.settings.updatedAt) {
      house.settings = {
        totalBudget: Number(incomingSettings.totalBudget) || 0,
        periodType: String(incomingSettings.periodType || 'MONTHLY').slice(0, 10),
        startDay: Number(incomingSettings.startDay) || 1,
        savingGoal: Number(incomingSettings.savingGoal) || 0,
        savingModeEnabled: Boolean(incomingSettings.savingModeEnabled),
        updatedAt: incomingSettings.updatedAt
      };
    }
  }

  persist();

  res.json({
    ok: true,
    expenses: Object.values(house.expenses),
    templates: Object.values(house.templates),
    // توفير الحجم: نرجع المراسلات فقط إذا رفع الهاتف مراسلات جديدة —
    // الجلب اللحظي للمراسلات يمر عبر /api/:code/messages (الإصدار 1.5+)
    messages: incomingMessages.length > 0 ? Object.values(house.messages) : [],
    settings: house.settings,
    serverTime: Date.now()
  });
});

// جلب المراسلات الجديدة فقط (مسار لحظي خفيف): استقصاء كل 2-5 ثوانٍ من التطبيق
// ?since= طابع زمني للسيرفر — يرجع المراسلات التي استقبلها السيرفر بعده فقط
app.get('/api/:code/messages', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const house = getHouse(code);
  const since = Number(req.query.since) || 0;
  const fresh = Object.values(house.messages).filter((m) => (m.receivedAt || 0) > since);
  res.json({ ok: true, messages: fresh, serverTime: Date.now() });
});

// رفع مراسلات جديدة/معدّلة (رسالة مرسلة أو تغيير حالة طلب) — دمج فوري ثم إرجاع الوارد الجديد
app.post('/api/:code/messages', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const house = getHouse(code);
  const body = req.body || {};
  mergeMessages(house, body.messages);
  persist();
  const since = Number(req.query.since) || 0;
  const fresh = Object.values(house.messages).filter((m) => (m.receivedAt || 0) > since);
  res.json({ ok: true, messages: fresh, serverTime: Date.now() });
});

// ---------- ملفات الوسائط (الإصدار 1.8: رسائل صوتية وصور) ----------

/**
 * رفع ملف وسائط (صوت/صورة) بصيغة JSON: { name, mime, data(base64) }.
 * نستخدم base64 داخل JSON بدل multipart لتبسيط عميل Retrofit، والحد
 * الأقصى 6 ميجابايت بعد فك الترميز (الصوت المضغوط والصور المصغّرة
 * من التطبيق أصغر من ذلك بكثير).
 * يُرجع: { ok: true, id } — الـ id هو ما تشير إليه المراسلة (mediaFile).
 */
app.post('/api/:code/files', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const body = req.body || {};
  const data = typeof body.data === 'string' ? body.data : '';
  if (!data) {
    return res.status(400).json({ ok: false, error: 'لا توجد بيانات الملف' });
  }

  let buf;
  try {
    buf = Buffer.from(data, 'base64');
  } catch (e) {
    return res.status(400).json({ ok: false, error: 'بيانات الملف تالفة (base64)' });
  }
  if (!buf || buf.length === 0) {
    return res.status(400).json({ ok: false, error: 'الملف فارغ' });
  }
  if (buf.length > MAX_FILE_BYTES) {
    return res.status(413).json({ ok: false, error: 'الملف أكبر من الحد المسموح (6 ميغابايت)' });
  }

  const house = getHouse(code);
  const id = crypto.randomUUID();
  const safeId = id.replace(/[^A-Za-z0-9_-]/g, '');
  try {
    fs.writeFileSync(path.join(houseFilesDir(code), safeId), buf);
  } catch (e) {
    return res.status(500).json({ ok: false, error: 'تعذر حفظ الملف على السيرفر' });
  }

  house.files[id] = {
    id,
    name: String(body.name || safeId).slice(0, 120),
    mime: String(body.mime || 'application/octet-stream').slice(0, 60),
    size: buf.length,
    createdAt: Date.now()
  };
  pruneHouseFiles(code, house);
  persist();
  res.json({ ok: true, id, size: buf.length });
});

/** تحميل ملف وسائط بمحتواه الثنائي الصحيح (صورة/صوت) */
app.get('/api/:code/files/:fileId', (req, res) => {
  const code = req.params.code;
  const fileId = String(req.params.fileId || '');
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const house = getHouse(code);
  const meta = house.files[fileId];
  if (!meta) {
    return res.status(404).json({ ok: false, error: 'الملف غير موجود (قد يكون حُذف بالتقليم)' });
  }
  const safeId = fileId.replace(/[^A-Za-z0-9_-]/g, '');
  const filePath = path.join(FILES_DIR, code, safeId);
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ ok: false, error: 'الملف غير موجود على القرص' });
  }
  res.setHeader('Content-Type', meta.mime || 'application/octet-stream');
  res.setHeader('Content-Length', meta.size || fs.statSync(filePath).size);
  res.setHeader('Cache-Control', 'public, max-age=604800');
  fs.createReadStream(filePath).pipe(res);
});

/** حذف ملف وسائط واحد (يستخدمه التقليم المستقبلي أو إعادة الضبط اليدوية) */
app.delete('/api/:code/files/:fileId', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  const house = getHouse(code);
  deleteHouseFile(code, house, String(req.params.fileId || ''));
  persist();
  res.json({ ok: true });
});

/**
 * دمج المراسلات بقاعدة "الأحدث يفوز" (مشترك بين /sync و /messages):
 *  - المقارنة صارمة (>) حتى لا يُعاد رفع نفس النسخة غير المتغيرة في كل مزامنة
 *    كاملة ويُحيّن طابع الاستقبال فيُرسل مجدداً للهاتف الآخر بلا داعٍ
 *  - receivedAt: طابع زمني من ساعة السيرفر عند قبول السجل، هو أساس فلترة
 *    الجلب اللحظي (since) — مستقل عن ساعات الهاتفين المتباعدتين
 */
function mergeMessages(house, rawList) {
  const incoming = Array.isArray(rawList) ? rawList : [];
  const VALID_STATUSES = ['PENDING', 'PURCHASED', 'REJECTED'];
  let accepted = 0;
  for (const raw of incoming) {
    if (!raw || typeof raw.id !== 'string') continue;
    const clean = {
      id: raw.id.slice(0, 64),
      type: raw.type === 'REQUEST' ? 'REQUEST' : 'TEXT',
      text: String(raw.text || '').slice(0, 500),
      suggestedPrice: Number(raw.suggestedPrice) || 0,
      status: VALID_STATUSES.includes(raw.status) ? raw.status : 'PENDING',
      purchasedPrice: Number(raw.purchasedPrice) || 0,
      expenseId: String(raw.expenseId || '').slice(0, 64),
      senderId: String(raw.senderId || '').slice(0, 64),
      // ملفات الوسائط (الإصدار 1.8): نوع الوسائط + معرّف الملف على السيرفر
      mediaType: VALID_MEDIA_TYPES.includes(raw.mediaType) ? raw.mediaType : '',
      mediaFile: String(raw.mediaFile || '').slice(0, 64),
      createdAt: Number(raw.createdAt) || 0,
      updatedAt: Number(raw.updatedAt) || 0,
      deleted: Boolean(raw.deleted)
    };
    const existing = house.messages[clean.id];
    if (!existing || clean.updatedAt > existing.updatedAt) {
      clean.receivedAt = Date.now();
      house.messages[clean.id] = clean;
      accepted++;
    } else if (existing && !existing.receivedAt) {
      // توافق خلفي: مراسلات قديمة أُنشئت قبل مسار اللحظية
      existing.receivedAt = Date.now();
      accepted++;
    }
  }

  // حد أقصى للمراسلات: نحافظ على الأحدث (طلبات وحالة أحدث أولاً)
  const mids = Object.keys(house.messages);
  if (mids.length > MAX_MESSAGES_PER_HOUSE) {
    mids.sort((a, b) => (house.messages[a].updatedAt || 0) - (house.messages[b].updatedAt || 0));
    const toRemoveM = mids.length - MAX_MESSAGES_PER_HOUSE;
    for (let i = 0; i < toRemoveM; i++) delete house.messages[mids[i]];
  }
  return incoming;
}

// حذف بيانات منزل معين بالكامل (إعادة ضبط اختيارية — لا يستخدمه التطبيق حالياً)
app.delete('/api/:code', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  delete store[code];
  // حذف ملفات الوسائط الخاصة بالمنزل من القرص أيضاً
  try {
    fs.rmSync(path.join(FILES_DIR, code), { recursive: true, force: true });
  } catch (e) {
    // لا يوجد مجلد ملفات — لا مشكلة
  }
  persist();
  res.json({ ok: true });
});

// 404 موحد بصيغة JSON
app.use((req, res) => {
  res.status(404).json({ ok: false, error: 'المسار غير موجود' });
});

// ---------- نبض البقاء (Keep-Alive) ----------
/**
 * الخطة المجانية على Render تُوقف السيرفر بعد 15 دقيقة من عدم النشاط،
 * ثم يحتاج أول طلب بعدها إلى 30-50 ثانية للاستيقاظ (وهو ما قد يُفشل
 * مزامنة الهاتف بسبب مهلة الاتصال).
 *
 * الحل: مؤقّت يرسل طلباً كل دقيقة إلى /health عبر الرابط الخارجي
 * للسيرفر نفسه، فيبقى نشاط السيرفر متجددداً على مدار الساعة
 * ولا ينام أبداً.
 *
 * آلية التشغيل:
 *  - على Render: يُفعّل تلقائياً لأن Render يضبط RENDER_EXTERNAL_URL حكماً
 *  - محلياً: معطّل افتراضياً (لا معنى لنبض محلي) — يمكن تفعيله للتجربة
 *    بضبط SELF_PING_URL=http://127.0.0.1:3000
 *  - سرعة النبض قابلة للضبط عبر SELF_PING_INTERVAL_MS (الافتراضي 60000 = دقيقة)
 */
const heartbeatState = { enabled: false, url: null, intervalMs: null, pings: 0, failures: 0, lastOkAt: null };

function startHeartbeat() {
  const rawBase = (process.env.SELF_PING_URL || process.env.RENDER_EXTERNAL_URL || '').trim();
  if (!rawBase) {
    console.log('[النبض] معطّل — لا يوجد رابط خارجي (طبيعي محلياً)؛ على Render يُفعّل تلقائياً');
    return;
  }

  const parsed = parseInt(process.env.SELF_PING_INTERVAL_MS || '', 10);
  const intervalMs = parsed && parsed >= 1000 ? parsed : 60000;
  const url = rawBase.replace(/\/+$/, '') + '/health';

  heartbeatState.enabled = true;
  heartbeatState.url = url;
  heartbeatState.intervalMs = intervalMs;

  const ping = async () => {
    heartbeatState.pings++;
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
      if (res.ok) {
        heartbeatState.lastOkAt = Date.now();
        console.log(`[النبض #${heartbeatState.pings}] ${res.status} — السيرفر مستيقظ`);
      } else {
        heartbeatState.failures++;
        console.warn(`[النبض #${heartbeatState.pings}] استجابة غير سليمة: ${res.status}`);
      }
    } catch (e) {
      heartbeatState.failures++;
      console.warn(`[النبض #${heartbeatState.pings}] فشل الاتصال: ${e.message}`);
    }
  };

  // نبضة فورية عند الإقلاع ثم نبضة كل دقيقة
  ping();
  setInterval(ping, intervalMs);
  console.log(`[النبض] مفعّل — طلب كل ${intervalMs / 1000} ثانية نحو ${url}`);
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`سيرفر مزامنة حصالة البيت يعمل على المنفذ ${PORT}`);
  startHeartbeat();
});

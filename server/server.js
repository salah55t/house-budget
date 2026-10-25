/**
 * ============================================
 *  سيرفر مزامنة "حصالة البيت" (House Budget Sync)
 * ============================================
 * وسيط خفيف بين هاتفين يشاركان نفس رمز الربط:
 *  - الهاتف الأول يرفع مصاريفه وتحديثات الميزانية عبر POST /api/:code/sync
 *  - الهاتف الثاني يسحب الحالة الموحدة عبر نفس الطلب أو GET /api/:code/state
 *  - الدمج بقاعدة "الأحدث يفوز" حسب حقل updatedAt لكل سجل
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

// حد أقصى للحماية من نمو غير طبيعي (يكفي لمصاريف بيت لسنوات)
const MAX_EXPENSES_PER_HOUSE = 5000;

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
  if (!store[code]) store[code] = { expenses: {}, settings: null };
  if (!store[code].expenses) store[code].expenses = {};
  return store[code];
}

function isValidCode(code) {
  return typeof code === 'string' && CODE_RE.test(code);
}

// ---------- التطبيق ----------
const app = express();
app.use(express.json({ limit: '1mb' }));

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
  res.json({ ok: true, service: 'house-budget-sync', time: Date.now() });
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

  // حد أقصى للحماية: عند التجاوز نحتفظ بالسجلات الأحدث تحديثاً
  const ids = Object.keys(house.expenses);
  if (ids.length > MAX_EXPENSES_PER_HOUSE) {
    ids.sort((a, b) => (house.expenses[a].updatedAt || 0) - (house.expenses[b].updatedAt || 0));
    const toRemove = ids.length - MAX_EXPENSES_PER_HOUSE;
    for (let i = 0; i < toRemove; i++) delete house.expenses[ids[i]];
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
    settings: house.settings,
    serverTime: Date.now()
  });
});

// حذف بيانات منزل معين بالكامل (إعادة ضبط اختيارية — لا يستخدمه التطبيق حالياً)
app.delete('/api/:code', (req, res) => {
  const code = req.params.code;
  if (!isValidCode(code)) {
    return res.status(400).json({ ok: false, error: 'رمز الربط غير صالح' });
  }
  delete store[code];
  persist();
  res.json({ ok: true });
});

// 404 موحد بصيغة JSON
app.use((req, res) => {
  res.status(404).json({ ok: false, error: 'المسار غير موجود' });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`سيرفر مزامنة حصالة البيت يعمل على المنفذ ${PORT}`);
});

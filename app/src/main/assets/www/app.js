/* ShiftDeck — work calendar + to-do list. All data stays on the phone (localStorage). */
(() => {
'use strict';

/* ================= icons ================= */
const P = {
  home:'M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  cal:'M6 5h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3zM3 10h18M8 3v4M16 3v4',
  list:'M10 6h10M10 12h10M10 18h10M3.5 6l1.5 1.5L8 4.5M3.5 12l1.5 1.5L8 10.5M3.5 18l1.5 1.5L8 16.5',
  more:'M5 4h5v5H5zM14 4h5v5h-5zM5 15h5v5H5zM14 15h5v5h-5z',
  plus:'M12 5v14M5 12h14',
  chevL:'M15 5l-7 7 7 7', chevR:'M9 5l7 7-7 7',
  clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2',
  printer:'M7 9V3h10v6M7 17H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2M7 14h10v7H7z',
  box:'M3 7.5l9-4.5 9 4.5v9L12 21l-9-4.5zM3 7.5l9 4.5 9-4.5M12 12v9',
  user:'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  users:'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M16 3.5a4 4 0 0 1 0 7.5M18 14a6 6 0 0 1 4 7',
  store:'M3 9l2-5h14l2 5M4 9v11h16V9M3 9h18M9 20v-6h6v6',
  cap:'M2 9l10-5 10 5-10 5zM6 11v5c3 2 9 2 12 0v-5',
  sun:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon:'M20 14A8 8 0 1 1 10 4a6.5 6.5 0 0 0 10 10z',
  sunrise:'M17 18a5 5 0 0 0-10 0M12 3v6M4.2 10.2l1.4 1.4M1 18h2M21 18h2M18.4 11.6l1.4-1.4M23 22H1M8.5 6.5L12 3l3.5 3.5',
  clip:'M9 3h6v3H9zM9 4.5H6a2 2 0 0 0-2 2V20a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6.5a2 2 0 0 0-2-2h-3M8 13l2.5 2.5L16 10',
  dot:'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z',
  bell:'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0',
  trash:'M3 6h18M8 6V4h8v2M6 6l1 15h10l1-15M10 11v6M14 11v6',
  check:'M5 12.5l4.5 4.5L19 7',
  x:'M6 6l12 12M18 6L6 18',
  phone:'M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2',
  repeat:'M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3',
  down:'M12 3v12M7 10l5 5 5-5M5 21h14', up:'M12 15V3M7 8l5-5 5 5M5 21h14',
  edit:'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  hash:'M5 9h14M5 15h14M10 3L8 21M16 3l-2 18',
  spark:'M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6',
  flag:'M5 21V4h12l-2.5 4L17 12H5',
  note:'M5 4h14v16H5zM9 9h6M9 13h6M9 17h3',
  sound:'M4 9v6h4l5 4V5L8 9zM16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12',
  vib:'M8 3h8v18H8zM4 8v8M20 8v8M1 10v4M23 10v4',
  broom:'M14 3l-4 9M6 12h8l2 9H4z'
};
const ic = (n, s = 24, w = 2.3) => `<svg class="ic" width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"><path d="${P[n] || P.dot}"/></svg>`;

/* ================= constants ================= */
const ETYPES = {
  shift:   { n:'Shift',        c:'#4D148C', ic:'store' },
  meeting: { n:'Meeting',      c:'#2F6FED', ic:'users' },
  training:{ n:'Training',     c:'#0FA3A3', ic:'cap' },
  pickup:  { n:'Pickup / Due', c:'#FF6200', ic:'box' },
  off:     { n:'Time Off',     c:'#18A957', ic:'sun' },
  other:   { n:'Other',        c:'#7A7290', ic:'dot' }
};
const TCATS = {
  print:    { n:'Print Job', c:'#FF6200', ic:'printer' },
  ship:     { n:'Shipping',  c:'#6B2FBF', ic:'box' },
  customer: { n:'Customer',  c:'#2F6FED', ic:'user' },
  counter:  { n:'Counter',   c:'#0FA3A3', ic:'store' },
  opening:  { n:'Opening',   c:'#E09400', ic:'sunrise' },
  closing:  { n:'Closing',   c:'#5B3E99', ic:'moon' },
  inventory:{ n:'Inventory', c:'#18A957', ic:'clip' },
  other:    { n:'Other',     c:'#7A7290', ic:'dot' }
};
const ORDER_CATS = ['print', 'ship', 'customer'];
const REMINDS = [[-1,'None'],[0,'At time'],[15,'15 min'],[30,'30 min'],[60,'1 hour'],[1440,'1 day']];
const DOW = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
const DOWL = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
const MON = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const TPL_META = {
  opening: { n:'Opening Checklist', ic:'sunrise', g:'linear-gradient(135deg,#FFB443,#FF6200)', cat:'opening' },
  mid:     { n:'Shift Change',      ic:'repeat',  g:'linear-gradient(135deg,#3A80F5,#2456C9)', cat:'counter' },
  closing: { n:'Closing Checklist', ic:'moon',    g:'linear-gradient(135deg,#7B3FD0,#3A0E73)', cat:'closing' }
};

/* ================= state ================= */
const KEY = 'shiftdeck.v1';
const DEF = {
  events: [], tasks: [],
  settings: { name:'', store:'', workplace:'FedEx Office', dark:false, haptics:true, sound:true, defRemind:15, welcomed:false },
  templates: {
    opening: ['Power on and warm up production printers','Check online and overnight print orders','Count the cash drawer','Stock paper, toner and packing supplies','Turn on self-serve copiers and computers','Review pickups due today'],
    mid:     ['Hand off open print jobs','Update order notes for next shift','Tidy counter and self-serve area','Check the pickup shelf'],
    closing: ['Stage outgoing packages for pickup','Clean counter and self-serve stations','Restock paper and shipping supplies','Close out the register','Check holds and pickup shelf','Power down equipment']
  }
};
let S;
try { S = JSON.parse(localStorage.getItem(KEY)); } catch (e) { S = null; }
if (!S || typeof S !== 'object') S = JSON.parse(JSON.stringify(DEF));
S.events = Array.isArray(S.events) ? S.events : [];
S.tasks = Array.isArray(S.tasks) ? S.tasks : [];
S.settings = Object.assign({}, DEF.settings, S.settings || {});
S.templates = Object.assign({}, DEF.templates, S.templates || {});

const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) {} syncReminders(); };
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

let view = 'today', selDay = null, calMonth = null, taskFilter = 'all', showDoneToday = false, lastRing = 1;
const TAB_ORDER = ['today', 'calendar', 'tasks', 'more'];

/* ================= utils ================= */
const $ = s => document.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
const pad = n => String(n).padStart(2, '0');
const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (s, n) => { const d = parse(s); d.setDate(d.getDate() + n); return ymd(d); };
const today = () => ymd(new Date());
const fmtTime = t => { if (!t) return ''; let [h, m] = t.split(':').map(Number); const ap = h >= 12 ? 'PM' : 'AM'; h = h % 12 || 12; return `${h}:${pad(m)} ${ap}`; };
const shortTime = t => fmtTime(t).replace(':00', '');
const niceDate = s => {
  const t = today();
  if (s === t) return 'Today';
  if (s === addDays(t, 1)) return 'Tomorrow';
  if (s === addDays(t, -1)) return 'Yesterday';
  const d = parse(s);
  return `${DOW[d.getDay()]}, ${MON[d.getMonth()].slice(0, 3)} ${d.getDate()}`;
};
const longDate = s => { const d = parse(s); return `${DOWL[d.getDay()]}, ${MON[d.getMonth()]} ${d.getDate()}`; };
const atTime = (date, time) => { const d = parse(date); if (time) { const [h, m] = time.split(':').map(Number); d.setHours(h, m, 0, 0); } return d; };
const evStart = e => atTime(e.date, e.allDay ? '' : e.start);
const evEnd = e => {
  if (e.allDay) { const d = parse(e.date); d.setDate(d.getDate() + 1); return d; }
  const s = evStart(e);
  if (!e.end) return new Date(s.getTime() + 3600e3);
  const en = atTime(e.date, e.end);
  if (en <= s) en.setDate(en.getDate() + 1); // overnight shift
  return en;
};
const evHours = e => e.allDay ? 0 : (evEnd(e) - evStart(e)) / 3600e3;
const sortEv = (a, b) => (a.date + (a.allDay ? '00:00' : a.start || '00:00')).localeCompare(b.date + (b.allDay ? '00:00' : b.start || '00:00'));
const eventsOn = d => S.events.filter(e => e.date === d).sort(sortEv);
const tasksDue = d => S.tasks.filter(t => t.due === d);
const doneToday = t => t.done && t.doneAt && ymd(new Date(t.doneAt)) === today();
const fmtHrs = h => (Math.round(h * 10) / 10).toString();
const relTime = ms => {
  const m = Math.round(ms / 60000);
  if (m < 1) return 'now';
  if (m < 60) return `in ${m} min`;
  const h = Math.floor(m / 60), r = m % 60;
  if (h < 24) return `in ${h}h${r ? ' ' + r + 'm' : ''}`;
  const d = Math.round(h / 24);
  return `in ${d} day${d > 1 ? 's' : ''}`;
};
const taskSort = (a, b) => {
  const pr = { high:0, normal:1, low:2 };
  return (a.due || '9999').localeCompare(b.due || '9999') || (a.time || '99').localeCompare(b.time || '99') || pr[a.pri || 'normal'] - pr[b.pri || 'normal'];
};

/* ================= native bridge ================= */
const N = window.Android || null;
const buzz = (ms = 12) => { if (!S.settings.haptics) return; try { N && N.vibrate ? N.vibrate(ms) : navigator.vibrate && navigator.vibrate(ms); } catch (e) {} };
let actx = null;
const sound = kind => {
  if (!S.settings.sound) return;
  try {
    actx = actx || new (window.AudioContext || window.webkitAudioContext)();
    const notes = kind === 'win' ? [523, 659, 784, 1047] : kind === 'del' ? [330, 220] : [660, 990];
    notes.forEach((f, i) => {
      const o = actx.createOscillator(), g = actx.createGain(), t = actx.currentTime + i * 0.075;
      o.type = 'sine'; o.frequency.setValueAtTime(f, t);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.18, t + 0.015); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
      o.connect(g); g.connect(actx.destination); o.start(t); o.stop(t + 0.18);
    });
  } catch (e) {}
};
function reminderList() {
  const out = [], now = Date.now();
  S.events.forEach(e => {
    if (e.remind == null || e.remind < 0) return;
    const base = e.allDay ? atTime(e.date, '08:00') : evStart(e);
    const t = base.getTime() - e.remind * 60000;
    if (t > now) out.push({ id:'e' + e.id, t, title:e.title, body:`${ETYPES[e.type] ? ETYPES[e.type].n : 'Event'} · ${niceDate(e.date)}${e.allDay ? '' : ' at ' + fmtTime(e.start)}` });
  });
  S.tasks.forEach(k => {
    if (k.done || !k.due || k.remind == null || k.remind < 0) return;
    const base = atTime(k.due, k.time || '09:00');
    const t = base.getTime() - k.remind * 60000;
    if (t > now) out.push({ id:'t' + k.id, t, title:'To-do: ' + k.title, body:`${(TCATS[k.cat] || TCATS.other).n}${k.order ? ' · #' + k.order : ''} · due ${niceDate(k.due)}${k.time ? ' ' + fmtTime(k.time) : ''}` });
  });
  return out.sort((a, b) => a.t - b.t).slice(0, 300);
}
function syncReminders() {
  if (!N || !N.syncReminders) return;
  try {
    const list = reminderList();
    N.syncReminders(JSON.stringify(list));
    if (list.length && N.ensureNotifyPermission) N.ensureNotifyPermission();
  } catch (e) {}
}

/* ================= fx: ripple, confetti, toast ================= */
document.addEventListener('pointerdown', e => {
  const el = e.target.closest('.rip,.btn,.chip,.sbtn,.nbtn,.setrow,.tplbtn,.qa button');
  if (!el) return;
  const r = el.getBoundingClientRect(), s = Math.max(r.width, r.height) * 2.2;
  const sp = document.createElement('span');
  sp.className = 'ripple';
  sp.style.cssText = `width:${s}px;height:${s}px;left:${e.clientX - r.left - s / 2}px;top:${e.clientY - r.top - s / 2}px`;
  el.appendChild(sp);
  setTimeout(() => sp.remove(), 650);
}, { passive:true });

const fx = $('#fx'), cx = fx.getContext('2d');
let parts = [], fxRun = false;
function sizeFx() { fx.width = innerWidth * devicePixelRatio; fx.height = innerHeight * devicePixelRatio; cx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0); }
sizeFx(); addEventListener('resize', sizeFx);
function confetti(x, y, n = 36, power = 1) {
  const cols = ['#FF6200', '#FF8A3D', '#4D148C', '#8E5BE0', '#FFC53D', '#18A957', '#ffffff'];
  for (let i = 0; i < n; i++) {
    const a = Math.random() * Math.PI * 2, v = (3 + Math.random() * 7) * power;
    parts.push({ x, y, vx:Math.cos(a) * v, vy:Math.sin(a) * v - 5 * power, r:Math.random() * Math.PI, vr:(Math.random() - .5) * .4, w:6 + Math.random() * 6, h:4 + Math.random() * 6, c:cols[i % cols.length], life:1 });
  }
  if (!fxRun) { fxRun = true; requestAnimationFrame(tick); }
}
function tick() {
  cx.clearRect(0, 0, innerWidth, innerHeight);
  parts = parts.filter(p => p.life > 0 && p.y < innerHeight + 40);
  parts.forEach(p => {
    p.vy += .32; p.vx *= .985; p.x += p.vx; p.y += p.vy; p.r += p.vr; p.life -= .011;
    cx.save(); cx.globalAlpha = Math.max(0, Math.min(1, p.life * 1.6)); cx.translate(p.x, p.y); cx.rotate(p.r);
    cx.fillStyle = p.c; cx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h); cx.restore();
  });
  if (parts.length) requestAnimationFrame(tick); else { fxRun = false; cx.clearRect(0, 0, innerWidth, innerHeight); }
}
function bigCelebrate() {
  confetti(innerWidth * .2, innerHeight * .45, 60, 1.3);
  setTimeout(() => confetti(innerWidth * .8, innerHeight * .4, 60, 1.3), 160);
  setTimeout(() => confetti(innerWidth * .5, innerHeight * .3, 70, 1.5), 320);
  sound('win'); buzz(40);
}
let toastT = null, toastUndo = null;
function toast(msg, undo) {
  const t = $('#toast');
  toastUndo = undo || null;
  t.innerHTML = `<span class="tx">${msg}</span>${undo ? '<button data-act="undo">Undo</button>' : ''}`;
  t.classList.add('show');
  clearTimeout(toastT);
  toastT = setTimeout(() => { t.classList.remove('show'); toastUndo = null; }, undo ? 4500 : 2400);
}

/* ================= rendering ================= */
const viewEl = $('#view');
function render(anim) {
  document.documentElement.classList.toggle('dark', !!S.settings.dark);
  document.body.classList.toggle('dark', !!S.settings.dark);
  if (N && N.setBars) try { N.setBars(S.settings.dark ? '#1D152C' : '#FFFFFF', S.settings.dark ? 1 : 0); } catch (e) {}
  const top = viewEl.scrollTop;
  viewEl.innerHTML = ({ today:vToday, calendar:vCalendar, tasks:vTasks, more:vMore })[view]();
  viewEl.classList.toggle('enter', !!anim);
  if (anim) { viewEl.scrollTop = 0; } else { viewEl.scrollTop = top; }
  // nav
  const idx = TAB_ORDER.indexOf(view);
  document.querySelectorAll('.tabs button').forEach((b, i) => b.classList.toggle('on', i === idx));
  $('#ind').style.transform = `translateX(${idx * 100}%)`;
  const fab = $('#fab');
  fab.classList.toggle('show', view === 'calendar' || view === 'tasks');
  // after-paint effects
  requestAnimationFrame(() => requestAnimationFrame(() => {
    const ring = viewEl.querySelector('.ring .val');
    if (ring) { const to = +ring.dataset.to; ring.style.strokeDashoffset = to; lastRing = to; }
    if (anim) viewEl.querySelectorAll('[data-count]').forEach(countUp);
  }));
}
function countUp(el) {
  const to = parseFloat(el.dataset.count), dec = String(el.dataset.count).includes('.') ? 1 : 0, t0 = performance.now();
  const step = now => { const k = Math.min(1, (now - t0) / 900), e = 1 - Math.pow(1 - k, 3); el.textContent = (to * e).toFixed(dec); if (k < 1) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}
function switchView(v) {
  if (v === view) { viewEl.scrollTo({ top:0, behavior:'smooth' }); return; }
  const dir = TAB_ORDER.indexOf(v) > TAB_ORDER.indexOf(view) ? 'go-r' : 'go-l';
  view = v;
  viewEl.classList.remove('go-r', 'go-l'); void viewEl.offsetWidth; viewEl.classList.add(dir);
  render(true); buzz(8);
}

/* ---------- pieces ---------- */
function taskRow(k, i = 0) {
  const cat = TCATS[k.cat] || TCATS.other, t = today();
  const late = !k.done && k.due && k.due < t;
  const meta = [];
  meta.push(`<span class="tag" style="--c:${cat.c}">${ic(cat.ic, 15, 2.5)}${cat.n}</span>`);
  if (k.due) meta.push(`<span class="${late ? 'late' : ''}">${ic('clock', 15, 2.5)}${late ? 'Overdue · ' : ''}${niceDate(k.due)}${k.time ? ' ' + shortTime(k.time) : ''}</span>`);
  if (k.order) meta.push(`<span>${ic('hash', 15, 2.5)}${esc(k.order)}</span>`);
  if (k.customer) meta.push(`<span>${ic('user', 15, 2.5)}${esc(k.customer)}</span>`);
  if (k.remind != null && k.remind >= 0 && k.due && !k.done) meta.push(`<span>${ic('bell', 15, 2.5)}</span>`);
  const call = k.phone && !k.done ? `<button class="callbtn" data-act="call" data-p="${esc(k.phone)}" aria-label="Call">${ic('phone', 22)}</button>` : '';
  return `<div class="swipe st ${k.done ? 'done' : ''}" style="--i:${i}" data-id="${k.id}">
    <div class="sw-bg"><span class="l">${ic('check', 22, 3)}${k.done ? 'Undo' : 'Done'}</span><span class="r">Delete${ic('trash', 22, 2.6)}</span></div>
    <div class="trow pri-${k.pri || 'normal'}" data-act="editTask" data-id="${k.id}">
      <button class="chk" data-act="toggle" data-id="${k.id}" aria-label="Complete"><svg viewBox="0 0 40 40"><circle class="cc" cx="20" cy="20" r="17"/><path class="cp" d="M12.5 20.5l5 5 10-11"/></svg></button>
      <div class="tmain"><div class="tt">${esc(k.title)}</div><div class="tm">${meta.join('')}</div></div>
      ${call}
    </div></div>`;
}
function eventRow(e, i = 0, showDate = false) {
  const ty = ETYPES[e.type] || ETYPES.other;
  const time = e.allDay ? `All day<small>${showDate ? niceDate(e.date) : ''}</small>` : `${shortTime(e.start)}<small>${e.end ? 'to ' + shortTime(e.end) : (showDate ? niceDate(e.date) : '')}</small>`;
  const hrs = e.type === 'shift' && !e.allDay && e.end ? ` · ${fmtHrs(evHours(e))} hrs` : '';
  return `<div class="ev st rip" style="--c:${ty.c};--i:${i}" data-act="editEvent" data-id="${e.id}">
    <div class="et">${time}</div>
    <div class="em"><div class="en">${esc(e.title)}</div><span class="ety">${ic(ty.ic, 14, 2.6)}${ty.n}${hrs}${e.remind >= 0 ? ' · ' + ic('bell', 13, 2.6) : ''}</span></div>
  </div>`;
}
const emptyBox = (icon, msg, i = 0) => `<div class="empty st" style="--i:${i}"><div class="eico">${ic(icon, 34, 2.2)}</div>${msg}</div>`;

/* ---------- TODAY ---------- */
function weekRange() { const d = new Date(); const s = new Date(d); s.setDate(d.getDate() - d.getDay()); const a = ymd(s); return [a, addDays(a, 6)]; }
function vToday() {
  const t = today(), now = new Date(), h = now.getHours();
  const greet = h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
  const nm = S.settings.name ? `, ${esc(S.settings.name.split(' ')[0])}` : '';
  const open = S.tasks.filter(k => !k.done && k.due && k.due <= t).sort(taskSort);
  const dToday = S.tasks.filter(doneToday);
  const total = open.length + dToday.length, pct = total ? dToday.length / total : 0;
  const C = 2 * Math.PI * 40, to = C * (1 - pct);
  const ring = `<div class="ring st" style="--i:1"><svg width="96" height="96" viewBox="0 0 96 96"><circle class="trk" cx="48" cy="48" r="40" fill="none" stroke-width="10"/><circle class="val" cx="48" cy="48" r="40" fill="none" stroke-width="10" stroke-linecap="round" stroke-dasharray="${C}" style="stroke-dashoffset:${lastRing === 1 ? C : lastRing}" data-to="${to}"/></svg><div class="num">${total ? `${dToday.length}/${total}` : ic('check', 30, 3)}<small>${total ? 'DONE' : 'CLEAR'}</small></div></div>`;
  const place = [S.settings.workplace, S.settings.store ? '#' + S.settings.store : ''].filter(Boolean).join(' · ');
  let i = 2;
  let html = `<section class="hero"><div class="blob b1"></div><div class="blob b2"></div><div class="blob b3"></div>
    <div class="hero-top st"><span class="badge">${ic('store', 16, 2.4)}${esc(place || 'My Store')}</span></div>
    <div class="hero-row"><div class="st" style="--i:1"><div class="hello">${greet}${nm}</div><div class="hello-date">${longDate(t)}</div></div>${ring}</div></section>`;
  html += `<div class="qa">
    <button class="q1 st rip" style="--i:${i++}" data-act="newTask"><span class="qi">${ic('plus', 28, 2.8)}</span>New To-Do</button>
    <button class="q2 st rip" style="--i:${i++}" data-act="newShift"><span class="qi">${ic('cal', 26)}</span>Add Shift</button>
    <button class="q3 st rip" style="--i:${i++}" data-act="newOrder"><span class="qi">${ic('printer', 26)}</span>Print Order</button>
    <button class="q4 st rip" style="--i:${i++}" data-act="checklists"><span class="qi">${ic('clip', 26)}</span>Checklists</button></div>`;
  // next up
  const upcoming = S.events.filter(e => evEnd(e) > now).sort(sortEv);
  const nx = upcoming.find(e => evStart(e) - now < 7 * 864e5);
  if (nx) {
    const ty = ETYPES[nx.type] || ETYPES.other, live = evStart(nx) <= now;
    html += `<div class="sec st" style="--i:${i++}">Next up</div>
    <div class="card next st rip" style="--i:${i++}" data-act="editEvent" data-id="${nx.id}"><div class="bar" style="background:${ty.c}"></div>
      <div class="nico ${live ? 'live' : ''}" style="background:${ty.c}">${ic(ty.ic, 30)}</div>
      <div style="min-width:0"><div class="nt">${esc(nx.title)}</div><div class="nm">${niceDate(nx.date)}${nx.allDay ? ' · All day' : ' · ' + fmtTime(nx.start) + (nx.end ? ' – ' + fmtTime(nx.end) : '')}</div></div>
      <div class="cd">${live ? 'NOW' : relTime(evStart(nx) - now)}</div></div>`;
  }
  // stats
  const [ws, we] = weekRange();
  const wkShifts = S.events.filter(e => e.type === 'shift' && e.date >= ws && e.date <= we);
  const hrs = wkShifts.reduce((a, e) => a + evHours(e), 0);
  html += `<div class="stats" style="margin-top:14px"><div class="stat st" style="--i:${i++}"><div class="sv" data-count="${fmtHrs(hrs)}">0</div><div class="sl">Hours this week</div></div>
    <div class="stat st" style="--i:${i++}"><div class="sv" data-count="${wkShifts.length}">0</div><div class="sl">Shifts this week</div></div>
    <div class="stat st" style="--i:${i++}"><div class="sv" data-count="${S.tasks.filter(k => !k.done).length}">0</div><div class="sl">Open to-dos</div></div></div>`;
  // schedule
  const evs = eventsOn(t);
  html += `<div class="sec st" style="--i:${i++}">Today's schedule<button class="link" data-act="newEventToday">+ Add</button></div>`;
  html += evs.length ? evs.map(e => eventRow(e, i++)).join('') : emptyBox('cal', 'Nothing on the calendar today.', i++);
  // todos
  html += `<div class="sec st" style="--i:${i++}">To-do today<button class="link" data-act="newTask">+ Add</button></div>`;
  html += open.length ? open.map(k => taskRow(k, i++)).join('') : emptyBox('check', total ? 'Everything\'s done. Nice work!' : 'No to-dos due today.', i++);
  if (dToday.length) {
    html += `<div class="grp st" style="--i:${i++}" data-act="toggleDoneToday"><span style="display:inline-flex;transition:transform .3s;transform:rotate(${showDoneToday ? 90 : 0}deg)">${ic('chevR', 18, 3)}</span> Done today <span class="n">${dToday.length}</span></div>`;
    if (showDoneToday) html += dToday.map(k => taskRow(k, i++)).join('');
  }
  return html;
}

/* ---------- CALENDAR ---------- */
function vCalendar() {
  const t = today();
  selDay = selDay || t;
  calMonth = calMonth || parse(selDay.slice(0, 8) + '01');
  const y = calMonth.getFullYear(), m = calMonth.getMonth();
  const first = new Date(y, m, 1), start = new Date(y, m, 1 - first.getDay());
  const weeks = Math.ceil((first.getDay() + new Date(y, m + 1, 0).getDate()) / 7);
  let cells = '';
  for (let k = 0; k < weeks * 7; k++) {
    const d = new Date(start); d.setDate(start.getDate() + k);
    const s = ymd(d), evs = eventsOn(s), tk = tasksDue(s).filter(x => !x.done);
    const dots = [...new Set(evs.map(e => (ETYPES[e.type] || ETYPES.other).c))].slice(0, 3).map(c => `<i style="--c:${c}"></i>`).join('') + (tk.length ? '<i style="--c:#FF8A3D;border-radius:2px"></i>' : '');
    cells += `<button class="day ${d.getMonth() !== m ? 'out' : ''} ${s === t ? 'today' : ''} ${s === selDay ? 'sel' : ''}" data-act="pickDay" data-d="${s}">${d.getDate()}<span class="dots">${dots}</span></button>`;
  }
  const evs = eventsOn(selDay), tks = tasksDue(selDay).sort(taskSort);
  const dayHrs = evs.filter(e => e.type === 'shift').reduce((a, e) => a + evHours(e), 0);
  let i = 3;
  let html = `<div class="calbar st"><h1>${MON[m]}<small>${y}</small></h1>
      <button class="nbtn" data-act="month" data-d="-1" aria-label="Previous month">${ic('chevL', 28, 2.8)}</button>
      <button class="nbtn" data-act="month" data-d="1" aria-label="Next month">${ic('chevR', 28, 2.8)}</button></div>
    <div class="calcard st" style="--i:1"><div class="wd">${DOW.map(d => `<span>${d.toUpperCase()}</span>`).join('')}</div>
      <div class="calgrid" id="calgrid">${cells}</div></div>
    <div class="dayhead st" style="--i:2"><h2>${niceDate(selDay) === 'Today' || niceDate(selDay) === 'Tomorrow' || niceDate(selDay) === 'Yesterday' ? niceDate(selDay) : longDate(selDay).split(',')[0]}<small>${longDate(selDay)}${dayHrs ? ` · ${fmtHrs(dayHrs)} hrs scheduled` : ''}</small></h2>
      ${selDay !== t ? `<button class="sbtn" data-act="goToday">Today</button>` : `<button class="sbtn" data-act="newTaskDay">${ic('plus', 18, 3)}To-Do</button>`}</div>`;
  html += evs.length ? evs.map(e => eventRow(e, i++)).join('') : '';
  if (tks.length) { html += `<div class="grp st" style="--i:${i++}">To-dos due <span class="n">${tks.length}</span></div>` + tks.map(k => taskRow(k, i++)).join(''); }
  if (!evs.length && !tks.length) html += emptyBox('cal', 'Nothing scheduled.<br>Tap the orange <b>+</b> to add a shift or event.', i++);
  return html;
}

/* ---------- TASKS ---------- */
function vTasks() {
  const t = today(), tm = addDays(t, 1);
  const F = [['all', 'All open'], ['today', 'Today'], ['upcoming', 'Upcoming'], ['orders', 'Orders'], ['done', 'Done']];
  const open = S.tasks.filter(k => !k.done).sort(taskSort);
  let html = `<div class="page-h st"><h1>To-Do</h1><button class="sbtn" data-act="checklists">${ic('clip', 20, 2.5)}Checklists</button></div>
    <div class="filters st" style="--i:1">${F.map(([k, n]) => `<button class="chip ${taskFilter === k ? 'on' : ''}" data-act="filter" data-f="${k}">${n}${k === 'all' ? ` · ${open.length}` : ''}</button>`).join('')}</div>`;
  let i = 2;
  const group = (title, list, cls = '') => list.length ? `<div class="grp st ${cls}" style="--i:${i++}">${title} <span class="n">${list.length}</span></div>` + list.map(k => taskRow(k, i++)).join('') : '';
  if (taskFilter === 'done') {
    const d = S.tasks.filter(k => k.done).sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0));
    html += d.length ? `<div style="padding:4px 16px 0;text-align:right"><button class="link sbtn st" data-act="clearDone">${ic('broom', 18, 2.5)}Clear all</button></div>` + group('Completed', d) : emptyBox('check', 'Completed to-dos will show here.', i++);
    return html;
  }
  let list = open;
  if (taskFilter === 'today') list = open.filter(k => k.due && k.due <= t);
  if (taskFilter === 'upcoming') list = open.filter(k => k.due && k.due > t);
  if (taskFilter === 'orders') list = open.filter(k => ORDER_CATS.includes(k.cat) || k.order);
  if (!list.length) return html + emptyBox(taskFilter === 'orders' ? 'printer' : 'list', taskFilter === 'orders' ? 'No open print or shipping orders.' : 'Nothing here. Tap <b>+</b> to add a to-do.', i++) ;
  html += group('Overdue', list.filter(k => k.due && k.due < t), 'late');
  html += group('Today', list.filter(k => k.due === t));
  html += group('Tomorrow', list.filter(k => k.due === tm));
  html += group('Later', list.filter(k => k.due && k.due > tm));
  html += group('No date', list.filter(k => !k.due));
  html += `<div class="empty st" style="--i:${i++};padding-top:16px;font-size:14px">Swipe right to finish · swipe left to delete</div>`;
  return html;
}

/* ---------- MORE ---------- */
function vMore() {
  const st = S.settings;
  const initials = (st.name || 'Me').split(/\s+/).map(s => s[0]).join('').slice(0, 2).toUpperCase();
  const row = (act, icon, title, sub, right = '', i = 0) => `<button class="setrow" data-act="${act}"><span class="si">${ic(icon, 24)}</span><span class="sx">${title}${sub ? `<small>${sub}</small>` : ''}</span>${right}</button>`;
  let i = 1;
  return `<div class="page-h st"><h1>More</h1></div>
  <div class="card prof st rip" style="--i:${i++}" data-act="editProfile"><div class="avatar">${esc(initials)}</div><div style="flex:1;min-width:0"><div class="pn">${esc(st.name || 'Set your name')}</div><div class="ps">${esc([st.workplace, st.store ? 'Store #' + st.store : ''].filter(Boolean).join(' · ') || 'Add your store')}</div></div>${ic('edit', 24)}</div>
  <div class="sec st" style="--i:${i++}">Settings</div>
  <div class="card st" style="--i:${i++};padding:4px 16px">
    ${row('setDark', 'moon', 'Dark mode', 'Easier on the eyes for closing shifts', `<span class="tgl ${st.dark ? 'on' : ''}"></span>`)}
    ${row('setHaptics', 'vib', 'Vibration', 'Buzz when you tap and finish things', `<span class="tgl ${st.haptics ? 'on' : ''}"></span>`)}
    ${row('setSound', 'sound', 'Sounds', 'Little chime when a to-do is done', `<span class="tgl ${st.sound ? 'on' : ''}"></span>`)}
    ${row('setRemind', 'bell', 'Default reminder', (REMINDS.find(r => r[0] === st.defRemind) || REMINDS[2])[1] + ' before', ic('chevR', 22))}
  </div>
  <div class="sec st" style="--i:${i++}">Checklists</div>
  <div class="card st" style="--i:${i++};padding:4px 16px">
    ${Object.keys(TPL_META).map(k => row('editTpl', TPL_META[k].ic, TPL_META[k].n, `${S.templates[k].length} items · tap to edit`, ic('chevR', 22)).replace('data-act="editTpl"', `data-act="editTpl" data-k="${k}"`)).join('')}
  </div>
  <div class="sec st" style="--i:${i++}">Your data</div>
  <div class="card st" style="--i:${i++};padding:4px 16px">
    ${row('exportData', 'up', 'Back up', 'Share a backup copy to email, Drive, etc.')}
    ${row('importData', 'down', 'Restore', 'Paste a backup to bring everything back')}
    ${row('clearDone', 'broom', 'Clear finished to-dos', `${S.tasks.filter(k => k.done).length} finished`)}
  </div>
  <div class="empty st" style="--i:${i++};font-size:14px">ShiftDeck 1.0 · Everything is saved on this phone only.</div>`;
}

/* ================= sheets ================= */
const wrap = $('#sheetWrap'), sheet = $('#sheet');
let F = {};
function openSheet(html, focusId) {
  sheet.innerHTML = `<div class="grab"></div>${html}`;
  sheet.scrollTop = 0;
  wrap.classList.add('open');
  buzz(10);
  if (focusId) setTimeout(() => { const el = document.getElementById(focusId); if (el && !el.value) el.focus(); }, 450);
}
function closeSheet() { wrap.classList.remove('open'); document.activeElement && document.activeElement.blur && document.activeElement.blur(); }
const isOpen = () => wrap.classList.contains('open');
const chipRow = (group, items, cur) => `<div class="chips" data-group="${group}">${items.map(([v, n, c, icn]) => `<button class="chip ${String(v) === String(cur) ? 'on' : ''}" data-act="chip" data-g="${group}" data-v="${v}">${c ? `<span class="dt" style="--c:${c}"></span>` : ''}${icn ? ic(icn, 18, 2.5) : ''}${n}</button>`).join('')}</div>`;
const val = id => (document.getElementById(id) || {}).value || '';

function eventSheet(ev, preset = {}) {
  const isNew = !ev;
  const type = ev ? ev.type : preset.type || 'shift';
  F = { kind:'event', id:ev && ev.id, type, remind:ev ? ev.remind : S.settings.defRemind, repeat:0, allDay:ev ? !!ev.allDay : type === 'off' };
  const d = ev ? ev.date : preset.date || selDay || today();
  openSheet(`<h2 class="sh-t">${isNew ? (type === 'shift' ? 'Add a shift' : 'Add to calendar') : 'Edit event'}</h2>
    <label class="lb">Type</label>${chipRow('type', Object.entries(ETYPES).map(([k, v]) => [k, v.n, v.c]), type)}
    <label class="lb">Title</label><input id="f_title" class="in" maxlength="80" placeholder="${type === 'shift' ? 'Shift (optional title)' : 'What is it?'}" value="${esc(ev ? ev.title : '')}">
    <label class="lb">Date</label><input id="f_date" type="date" class="in" value="${d}">
    <div id="f_times" style="${F.allDay ? 'display:none' : ''}"><div class="row2"><div><label class="lb">Start</label><input id="f_start" type="time" class="in" value="${ev ? ev.start || '' : (type === 'shift' ? '08:00' : '09:00')}"></div>
      <div><label class="lb">End</label><input id="f_end" type="time" class="in" value="${ev ? ev.end || '' : (type === 'shift' ? '16:30' : '10:00')}"></div></div></div>
    <button class="setrow" data-act="fAllDay" style="margin-top:8px"><span class="si">${ic('sun', 24)}</span><span class="sx">All day</span><span class="tgl ${F.allDay ? 'on' : ''}" id="f_ad"></span></button>
    ${isNew ? `<label class="lb">Repeat weekly</label>${chipRow('repeat', [[0, 'No'], [4, '4 weeks'], [8, '8 weeks'], [12, '12 weeks']], 0)}` : ''}
    <label class="lb">Remind me</label>${chipRow('remind', REMINDS, F.remind)}
    <label class="lb">Notes</label><textarea id="f_notes" class="in" placeholder="Anything to remember">${esc(ev ? ev.notes || '' : '')}</textarea>
    <div class="sh-actions"><button class="btn primary" data-act="saveEvent">${ic('check', 26, 3)}${isNew ? 'Save' : 'Save changes'}</button>
    ${isNew ? '<button class="btn ghost" data-act="closeSheet">Cancel</button>' : `<button class="btn danger" data-act="delEvent">${ic('trash', 22)}Delete</button>`}</div>`, type === 'shift' ? null : 'f_title');
}
function taskSheet(k, preset = {}) {
  const isNew = !k;
  const cat = k ? k.cat : preset.cat || 'other';
  F = { kind:'task', id:k && k.id, cat, pri:k ? k.pri || 'normal' : 'normal', remind:k ? (k.remind == null ? -1 : k.remind) : -1 };
  const due = k ? k.due || '' : (preset.due !== undefined ? preset.due : today());
  const showOrder = ORDER_CATS.includes(cat) || (k && (k.order || k.customer || k.phone));
  openSheet(`<h2 class="sh-t">${isNew ? (preset.cat === 'print' ? 'New print order' : 'New to-do') : 'Edit to-do'}</h2>
    <label class="lb">What needs doing?</label><input id="f_title" class="in" maxlength="120" placeholder="${preset.cat === 'print' ? 'e.g. 50 color flyers, 11x17' : 'e.g. Call customer about banner'}" value="${esc(k ? k.title : '')}">
    <label class="lb">Category</label>${chipRow('cat', Object.entries(TCATS).map(([key, v]) => [key, v.n, null, v.ic]), cat)}
    <div id="f_order" style="${showOrder ? '' : 'display:none'}">
      <div class="row2"><div><label class="lb">Order / Job #</label><input id="f_ord" class="in" inputmode="text" value="${esc(k ? k.order || '' : '')}" placeholder="Optional"></div>
      <div><label class="lb">Customer</label><input id="f_cust" class="in" value="${esc(k ? k.customer || '' : '')}" placeholder="Name"></div></div>
      <label class="lb">Customer phone</label><input id="f_phone" class="in" type="tel" value="${esc(k ? k.phone || '' : '')}" placeholder="Tap to call from the list">
    </div>
    <label class="lb">Priority</label>${chipRow('pri', [['high', 'High', '#E5374A'], ['normal', 'Normal', '#6E6684'], ['low', 'Low', '#B9B2C9']], F.pri)}
    <label class="lb">Due</label><input id="f_due" type="date" class="in" value="${due}">
    <div class="qd"><button class="chip" data-act="qdue" data-v="0">Today</button><button class="chip" data-act="qdue" data-v="1">Tomorrow</button><button class="chip" data-act="qdue" data-v="">No date</button></div>
    <label class="lb">Time (optional)</label><input id="f_time" type="time" class="in" value="${k ? k.time || '' : ''}">
    <label class="lb">Remind me</label>${chipRow('remind', REMINDS, F.remind)}
    <label class="lb">Notes</label><textarea id="f_notes" class="in" placeholder="Paper type, finish, pickup details…">${esc(k ? k.notes || '' : '')}</textarea>
    <div class="sh-actions"><button class="btn primary" data-act="saveTask">${ic('check', 26, 3)}${isNew ? 'Add to-do' : 'Save changes'}</button>
    ${isNew ? '<button class="btn ghost" data-act="closeSheet">Cancel</button>' : `<button class="btn danger" data-act="delTask">${ic('trash', 22)}Delete</button>`}</div>`, 'f_title');
}
function checklistSheet() {
  openSheet(`<h2 class="sh-t">Checklists</h2><p class="sh-s">One tap adds the whole list to today's to-dos.</p><div style="height:10px"></div>
    ${Object.entries(TPL_META).map(([k, m]) => `<button class="tplbtn" style="background:${m.g}" data-act="runTpl" data-k="${k}"><span class="qi">${ic(m.ic, 28)}</span><span>${m.n}<small>${S.templates[k].length} items</small></span></button>`).join('')}
    <button class="btn ghost" data-act="editTplFromSheet">${ic('edit', 22)}Edit checklists</button>`);
}
function profileSheet(first) {
  const st = S.settings;
  openSheet(`<h2 class="sh-t">${first ? 'Welcome to ShiftDeck 👋' : 'Your profile'}</h2>
    <p class="sh-s">${first ? 'Your work calendar and to-do list in one place. Let\'s set it up — takes 10 seconds.' : 'Shown at the top of your Today screen.'}</p>
    <label class="lb">Your name</label><input id="f_name" class="in" value="${esc(st.name)}" placeholder="First name">
    <label class="lb">Workplace</label><input id="f_work" class="in" value="${esc(st.workplace)}" placeholder="Where you work">
    <label class="lb">Store number</label><input id="f_store" class="in" inputmode="numeric" value="${esc(st.store)}" placeholder="Optional">
    <div class="sh-actions"><button class="btn primary" data-act="saveProfile">${first ? "Let's go" : 'Save'}</button></div>`, first ? 'f_name' : null);
}
function tplSheet(k) {
  F = { kind:'tpl', k };
  const m = TPL_META[k];
  openSheet(`<h2 class="sh-t">${m.n}</h2><p class="sh-s">One item per line.</p>
    <textarea id="f_tpl" class="in" style="min-height:280px">${esc(S.templates[k].join('\n'))}</textarea>
    <div class="sh-actions"><button class="btn primary" data-act="saveTpl">Save checklist</button><button class="btn ghost" data-act="resetTpl">Reset to default</button></div>`);
}
function remindSheet() {
  F = { kind:'defRemind', remind:S.settings.defRemind };
  openSheet(`<h2 class="sh-t">Default reminder</h2><p class="sh-s">Used for new calendar events.</p><div style="height:8px"></div>${chipRow('remind', REMINDS, F.remind)}
    <div class="sh-actions"><button class="btn primary" data-act="saveDefRemind">Save</button></div>`);
}

/* ================= actions ================= */
const findT = id => S.tasks.find(k => k.id === id);
const findE = id => S.events.find(e => e.id === id);
function shakeEl(id) { const el = document.getElementById(id); if (!el) return; el.classList.remove('shake'); void el.offsetWidth; el.classList.add('shake'); el.focus(); buzz(30); }

function completeTask(id, el) {
  const k = findT(id); if (!k) return;
  if (k.done) { k.done = false; k.doneAt = null; save(); render(); toast('Moved back to open'); return; }
  const node = el || document.querySelector(`.swipe[data-id="${id}"]`);
  if (node) {
    node.classList.add('completing');
    const c = node.querySelector('.chk').getBoundingClientRect();
    confetti(c.left + c.width / 2, c.top + c.height / 2, 28, .8);
  }
  sound('done'); buzz(18);
  setTimeout(() => {
    k.done = true; k.doneAt = Date.now(); save();
    const t = today(), left = S.tasks.filter(x => !x.done && x.due && x.due <= t).length;
    const wasToday = k.due && k.due <= t;
    if (node && view !== 'today') { node.style.height = node.offsetHeight + 'px'; void node.offsetHeight; node.classList.add('gone'); setTimeout(render, 330); } else render();
    if (wasToday && left === 0) { setTimeout(bigCelebrate, 250); toast('🎉 Everything due today is done!'); }
    else toast('Nice! To-do finished', () => { k.done = false; k.doneAt = null; save(); render(); });
  }, 480);
}
function deleteTask(id, el) {
  const idx = S.tasks.findIndex(k => k.id === id); if (idx < 0) return;
  const [k] = S.tasks.splice(idx, 1); save(); sound('del'); buzz(25);
  const node = el || document.querySelector(`.swipe[data-id="${id}"]`);
  const after = () => render();
  if (node) { node.style.height = node.offsetHeight + 'px'; void node.offsetHeight; node.classList.add('gone'); setTimeout(after, 330); } else after();
  toast('To-do deleted', () => { S.tasks.splice(idx, 0, k); save(); render(); });
}
function addTemplate(key) {
  const m = TPL_META[key], t = today();
  const existing = new Set(S.tasks.filter(k => !k.done && k.due === t).map(k => k.title));
  const items = S.templates[key].filter(x => x.trim() && !existing.has(x.trim()));
  items.forEach(x => S.tasks.push({ id:uid(), title:x.trim(), cat:m.cat, pri:'normal', due:t, time:'', done:false, remind:-1, created:Date.now() }));
  save(); closeSheet();
  if (items.length) { confetti(innerWidth / 2, innerHeight * .6, 40, 1); sound('done'); toast(`Added ${items.length} to-dos from ${m.n}`); }
  else toast('Those are already on today\'s list');
  view = 'today'; render(true);
}

const ACT = {
  tab: a => switchView(a.dataset.v),
  fab: () => { buzz(14); view === 'tasks' ? taskSheet(null, { due: taskFilter === 'upcoming' ? addDays(today(), 1) : taskFilter === 'all' ? '' : today(), cat: taskFilter === 'orders' ? 'print' : undefined }) : eventSheet(null, { date:selDay }); },
  closeSheet,
  undo: () => { const u = toastUndo; $('#toast').classList.remove('show'); toastUndo = null; u && u(); },
  newTask: () => taskSheet(null, { due:today() }),
  newTaskDay: () => taskSheet(null, { due:selDay }),
  newShift: () => eventSheet(null, { type:'shift', date:today() }),
  newEventToday: () => eventSheet(null, { type:'other', date:today() }),
  newOrder: () => taskSheet(null, { cat:'print', due:today() }),
  checklists: checklistSheet,
  editTask: a => { const k = findT(a.dataset.id); k && taskSheet(k); },
  editEvent: a => { const e = findE(a.dataset.id); e && eventSheet(e); },
  toggle: a => completeTask(a.dataset.id, a.closest('.swipe')),
  call: a => { buzz(); if (N && N.dial) N.dial(a.dataset.p); else location.href = 'tel:' + a.dataset.p; },
  toggleDoneToday: () => { showDoneToday = !showDoneToday; render(); },
  filter: a => { taskFilter = a.dataset.f; buzz(8); render(true); },
  pickDay: a => { selDay = a.dataset.d; const d = parse(selDay); if (d.getMonth() !== calMonth.getMonth()) { const dir = d > calMonth ? 'mL' : 'mR'; calMonth = new Date(d.getFullYear(), d.getMonth(), 1); render(); slideGrid(dir); } else render(); buzz(8); },
  month: a => changeMonth(+a.dataset.d),
  goToday: () => { selDay = today(); calMonth = parse(selDay.slice(0, 8) + '01'); render(true); },
  chip: a => {
    const g = a.dataset.g; let v = a.dataset.v;
    if (g === 'remind' || g === 'repeat') v = +v;
    F[g] = v; buzz(8);
    a.parentElement.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', c === a));
    if (g === 'cat') { const o = $('#f_order'); if (o && ORDER_CATS.includes(v)) o.style.display = ''; }
    if (g === 'type' && F.kind === 'event') {
      const ti = $('#f_title'); if (ti) ti.placeholder = v === 'shift' ? 'Shift (optional title)' : 'What is it?';
      if (v === 'off' && !F.allDay) ACT.fAllDay();
    }
  },
  fAllDay: () => { F.allDay = !F.allDay; $('#f_ad').classList.toggle('on', F.allDay); $('#f_times').style.display = F.allDay ? 'none' : ''; buzz(8); },
  qdue: a => { $('#f_due').value = a.dataset.v === '' ? '' : addDays(today(), +a.dataset.v); a.parentElement.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', c === a)); buzz(8); },
  saveEvent: () => {
    const date = val('f_date');
    if (!date) return shakeEl('f_date');
    const start = val('f_start'), end = val('f_end');
    if (!F.allDay && !start) return shakeEl('f_start');
    const title = val('f_title').trim() || ETYPES[F.type].n;
    const base = { title, type:F.type, allDay:F.allDay, start:F.allDay ? '' : start, end:F.allDay ? '' : end, remind:F.remind, notes:val('f_notes').trim() };
    if (F.id) { Object.assign(findE(F.id), base, { date }); }
    else {
      const n = Math.max(1, F.repeat || 1), gid = n > 1 ? uid() : null;
      for (let w = 0; w < n; w++) S.events.push(Object.assign({ id:uid(), date:addDays(date, w * 7), gid }, base));
    }
    save(); closeSheet(); sound('done');
    if (view === 'calendar') { selDay = date; calMonth = parse(date.slice(0, 8) + '01'); }
    render(); toast(F.id ? 'Event updated' : (F.repeat > 1 ? `Added ${F.repeat} weekly ${ETYPES[F.type].n.toLowerCase()}s` : `${ETYPES[F.type].n} added to ${niceDate(date)}`));
  },
  delEvent: () => {
    const e = findE(F.id); if (!e) return;
    const series = e.gid ? S.events.filter(x => x.gid === e.gid) : [];
    if (series.length > 1) {
      openSheet(`<h2 class="sh-t">Delete repeating event?</h2><p class="sh-s">This is one of ${series.length} weekly events.</p>
        <div class="sh-actions"><button class="btn danger" data-act="delEvOne">Just this one</button><button class="btn danger" data-act="delEvAll">All upcoming in the series</button><button class="btn ghost" data-act="closeSheet">Cancel</button></div>`);
    } else ACT.delEvOne();
  },
  delEvOne: () => {
    const idx = S.events.findIndex(x => x.id === F.id); if (idx < 0) return;
    const [e] = S.events.splice(idx, 1); save(); closeSheet(); sound('del'); buzz(25); render();
    toast('Event deleted', () => { S.events.push(e); save(); render(); });
  },
  delEvAll: () => {
    const e = findE(F.id); if (!e) return;
    const removed = S.events.filter(x => x.gid === e.gid && x.date >= e.date);
    S.events = S.events.filter(x => !removed.includes(x)); save(); closeSheet(); sound('del'); buzz(25); render();
    toast(`Deleted ${removed.length} events`, () => { S.events.push(...removed); save(); render(); });
  },
  saveTask: () => {
    const title = val('f_title').trim();
    if (!title) return shakeEl('f_title');
    const data = { title, cat:F.cat, pri:F.pri, due:val('f_due'), time:val('f_time'), remind:F.remind, notes:val('f_notes').trim(),
      order:val('f_ord').trim(), customer:val('f_cust').trim(), phone:val('f_phone').trim() };
    if (F.id) Object.assign(findT(F.id), data);
    else S.tasks.push(Object.assign({ id:uid(), done:false, created:Date.now() }, data));
    save(); closeSheet(); sound('done'); render();
    toast(F.id ? 'To-do updated' : (data.due ? `Added for ${niceDate(data.due)}` : 'To-do added'));
  },
  delTask: () => { closeSheet(); setTimeout(() => deleteTask(F.id), 250); },
  runTpl: a => addTemplate(a.dataset.k),
  editTpl: a => tplSheet(a.dataset.k),
  editTplFromSheet: () => { closeSheet(); view = 'more'; render(true); },
  saveTpl: () => { S.templates[F.k] = val('f_tpl').split('\n').map(s => s.trim()).filter(Boolean); save(); closeSheet(); render(); toast('Checklist saved'); },
  resetTpl: () => { $('#f_tpl').value = DEF.templates[F.k].join('\n'); buzz(); },
  editProfile: () => profileSheet(false),
  saveProfile: () => {
    Object.assign(S.settings, { name:val('f_name').trim(), workplace:val('f_work').trim(), store:val('f_store').trim(), welcomed:true });
    save(); closeSheet(); render(true);
    if (S.settings.name) setTimeout(() => confetti(innerWidth / 2, 160, 40, 1), 300);
  },
  setDark: () => { S.settings.dark = !S.settings.dark; save(); render(); },
  setHaptics: () => { S.settings.haptics = !S.settings.haptics; save(); render(); buzz(20); },
  setSound: () => { S.settings.sound = !S.settings.sound; save(); render(); sound('done'); },
  setRemind: remindSheet,
  saveDefRemind: () => { S.settings.defRemind = F.remind; save(); closeSheet(); render(); },
  clearDone: () => {
    const done = S.tasks.filter(k => k.done); if (!done.length) return toast('Nothing to clear');
    S.tasks = S.tasks.filter(k => !k.done); save(); render(); sound('del');
    toast(`Cleared ${done.length} finished to-dos`, () => { S.tasks.push(...done); save(); render(); });
  },
  exportData: () => {
    const json = JSON.stringify({ app:'ShiftDeck', v:1, at:new Date().toISOString(), data:S });
    if (N && N.share) N.share('ShiftDeck backup ' + today(), json);
    else if (navigator.clipboard) navigator.clipboard.writeText(json).then(() => toast('Backup copied to clipboard'));
  },
  importData: () => openSheet(`<h2 class="sh-t">Restore a backup</h2><p class="sh-s">Paste the backup text you saved. This replaces what's on this phone.</p>
    <textarea id="f_imp" class="in" style="min-height:200px;font-size:14px" placeholder="Paste backup here"></textarea>
    <div class="sh-actions"><button class="btn purple" data-act="doImport">${ic('down', 24)}Restore</button><button class="btn ghost" data-act="closeSheet">Cancel</button></div>`, 'f_imp'),
  doImport: () => {
    try {
      const o = JSON.parse(val('f_imp')); const d = o.data || o;
      if (!Array.isArray(d.events) || !Array.isArray(d.tasks)) throw 0;
      S.events = d.events; S.tasks = d.tasks; S.settings = Object.assign({}, DEF.settings, d.settings || {}); S.templates = Object.assign({}, DEF.templates, d.templates || {});
      save(); closeSheet(); render(true); toast(`Restored ${S.events.length} events and ${S.tasks.length} to-dos`);
    } catch (e) { shakeEl('f_imp'); toast('That doesn\'t look like a ShiftDeck backup'); }
  }
};
function changeMonth(d) {
  calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() + d, 1);
  const t = today();
  selDay = t.slice(0, 7) === ymd(calMonth).slice(0, 7) ? t : ymd(calMonth);
  render(); slideGrid(d > 0 ? 'mL' : 'mR'); buzz(10);
}
function slideGrid(cls) { const g = $('#calgrid'); if (g) { g.classList.remove('mL', 'mR'); void g.offsetWidth; g.classList.add(cls); } }

let swipedAt = 0;
document.addEventListener('click', e => {
  if (Date.now() - swipedAt < 350) { e.preventDefault(); return; }
  const a = e.target.closest('[data-act]');
  if (!a) return;
  const fn = ACT[a.dataset.act];
  if (fn) { e.preventDefault(); fn(a, e); }
});

/* ---------- swipe gestures (task rows + calendar months) ---------- */
let sw = null;
document.addEventListener('touchstart', e => {
  if (isOpen()) return;
  const t = e.touches[0];
  const row = e.target.closest('.swipe');
  const cal = !row && e.target.closest('#calgrid');
  if (!row && !cal) return;
  sw = { row, cal, inner:row && row.querySelector('.trow'), x:t.clientX, y:t.clientY, dx:0, lock:null };
}, { passive:true });
document.addEventListener('touchmove', e => {
  if (!sw) return;
  const t = e.touches[0], dx = t.clientX - sw.x, dy = t.clientY - sw.y;
  if (sw.lock === null && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) sw.lock = Math.abs(dx) > Math.abs(dy) * 1.2 ? 'x' : 'y';
  if (sw.lock !== 'x') return;
  e.preventDefault();
  sw.dx = dx;
  if (sw.row) {
    const d = dx > 0 ? Math.min(dx, innerWidth) : Math.max(dx, -innerWidth);
    sw.inner.style.transition = 'none';
    sw.inner.style.transform = `translateX(${d}px)`;
    sw.row.classList.toggle('sl', dx > 0); sw.row.classList.toggle('sr', dx < 0);
    if (Math.abs(dx) > 90 && !sw.buzzed) { buzz(10); sw.buzzed = true; }
    if (Math.abs(dx) < 90) sw.buzzed = false;
  }
}, { passive:false });
document.addEventListener('touchend', () => {
  if (!sw) return;
  const s = sw; sw = null;
  if (s.lock !== 'x') return;
  swipedAt = Date.now();
  if (s.cal) { if (Math.abs(s.dx) > 50) changeMonth(s.dx < 0 ? 1 : -1); return; }
  s.inner.style.transition = '';
  const id = s.row.dataset.id;
  if (s.dx > 90) { s.inner.style.transform = ''; completeTask(id, s.row); }
  else if (s.dx < -90) { s.inner.style.transform = `translateX(-110%)`; setTimeout(() => deleteTask(id, s.row), 200); }
  else s.inner.style.transform = '';
});

/* ---------- android back button ---------- */
window.onAndroidBack = () => {
  if (isOpen()) { closeSheet(); return true; }
  if (view !== 'today') { switchView('today'); return true; }
  return false;
};
// refresh "next up" countdowns & date rollover
let lastDay = today();
setInterval(() => { if (isOpen()) return; if (today() !== lastDay) { lastDay = today(); selDay = null; calMonth = null; } if (view === 'today') render(); }, 60000);
document.addEventListener('visibilitychange', () => { if (!document.hidden && !isOpen()) render(); });

/* ================= boot ================= */
document.querySelectorAll('.tabs .ti').forEach((el, i) => el.innerHTML = ic(['home', 'cal', 'list', 'more'][i], 28, 2.3));
$('#fab').innerHTML = ic('plus', 36, 3);
render(false);
syncReminders();
setTimeout(() => {
  $('#splash').classList.add('hide');
  render(true);
  setTimeout(() => { $('#splash').remove(); if (!S.settings.welcomed) profileSheet(true); }, 520);
}, 1300);
})();

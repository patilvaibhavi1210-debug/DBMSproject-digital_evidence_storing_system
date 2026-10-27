/* ===================================================================
   Evidence Tracker - frontend logic (plain JavaScript, no libraries)
   =================================================================== */

const CASE_STATUS = ['Open', 'Under Investigation', 'Closed'];
const EV_STATUS   = ['In Storage', 'Checked Out', 'In Lab', 'In Court', 'Released', 'Disposed'];
const MOVE_TYPES  = ['Transfer', 'Check-out', 'Check-in', 'Lab Submission', 'Court Submission'];
const ROLES       = ['Officer', 'Analyst', 'Admin'];

let token = localStorage.getItem('token');
let me = JSON.parse(localStorage.getItem('me') || 'null');
let currentView = 'dashboard';
let casesCache = {};

/* ---------------- small helpers ---------------- */
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const day = (s) => (s ? String(s).slice(0, 10) : '');
const when = (s) => (s ? String(s).slice(0, 16) : '');
const today = () => new Date().toISOString().slice(0, 10);
const chip = (s) => `<span class="chip s-${String(s).toLowerCase().replace(/\s+/g, '-')}">${esc(s)}</span>`;
const opts = (list, selected) => list.map((o) => {
  const v = typeof o === 'object' ? o.v : o, t = typeof o === 'object' ? o.t : o;
  return `<option value="${esc(v)}" ${String(v) === String(selected) ? 'selected' : ''}>${esc(t)}</option>`;
}).join('');

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'show' + (bad ? ' bad' : '');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.className = ''), 3200);
}

async function api(path, method = 'GET', body) {
  const res = await fetch('/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Token': token || '' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== '/login') { logout(); throw new Error('Session expired. Please log in again.'); }
  if (!res.ok) throw new Error(data.error || 'Request failed');
  return data;
}

/* wrap an action: run it, show errors as a toast */
async function run(fn, successMsg) {
  try { await fn(); if (successMsg) toast(successMsg); }
  catch (e) { toast(e.message, true); }
}

/* ---------------- modal + generic form ---------------- */
function openModal(title, html) {
  $('modal-title').textContent = title;
  $('modal-body').innerHTML = html;
  if (!$('modal').open) $('modal').showModal();
}
function closeModal() { $('modal').close(); }

/* fields: [{name,label,type,options,required,value,placeholder,half}] */
function openForm(title, fields, onSubmit, submitLabel = 'Save') {
  const inputs = fields.map((f) => {
    let el;
    if (f.type === 'select') el = `<select name="${f.name}" ${f.required ? 'required' : ''}>${f.optional ? '<option value="">- none -</option>' : ''}${opts(f.options, f.value)}</select>`;
    else if (f.type === 'textarea') el = `<textarea name="${f.name}" placeholder="${esc(f.placeholder)}" ${f.required ? 'required' : ''}>${esc(f.value)}</textarea>`;
    else el = `<input name="${f.name}" type="${f.type || 'text'}" value="${esc(f.value)}" placeholder="${esc(f.placeholder)}" ${f.required ? 'required' : ''}>`;
    return `<label>${esc(f.label)}${el}</label>`;
  }).join('');
  openModal(title, `<form id="gform"><div class="grid2">${inputs}</div>
    <p id="gerr" class="error" role="alert"></p>
    <div class="form-actions"><button type="button" class="btn" onclick="closeModal()">Cancel</button>
    <button class="btn primary">${esc(submitLabel)}</button></div></form>`);
  $('gform').onsubmit = async (ev) => {
    ev.preventDefault();
    const data = Object.fromEntries(new FormData(ev.target).entries());
    try { await onSubmit(data); closeModal(); }
    catch (e) { $('gerr').textContent = e.message; }
  };
}

/* ---------------- login / logout ---------------- */
async function doLogin(ev) {
  ev.preventDefault();
  $('l-error').textContent = '';
  try {
    const r = await api('/login', 'POST', { email: $('l-email').value, password: $('l-pass').value });
    token = r.token; me = r;
    localStorage.setItem('token', token);
    localStorage.setItem('me', JSON.stringify(me));
    start();
  } catch (e) { $('l-error').textContent = e.message; }
}

function logout() {
  token = null; me = null;
  localStorage.removeItem('token'); localStorage.removeItem('me');
  $('app').hidden = true; $('login').hidden = false;
}

function start() {
  if (!token || !me) { $('login').hidden = false; $('app').hidden = true; return; }
  $('login').hidden = true; $('app').hidden = false;
  $('who-name').textContent = me.name;
  $('who-role').textContent = me.role;
  go(currentView);
}

document.querySelectorAll('#nav button').forEach((b) => (b.onclick = () => go(b.dataset.view)));

const TITLES = { dashboard: 'Dashboard', cases: 'Cases', evidence: 'Evidence', locations: 'Storage locations', tags: 'Tags', users: 'Team' };
function go(view) {
  currentView = view;
  document.querySelectorAll('#nav button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  $('page-title').textContent = TITLES[view];
  run(() => views[view]());
}
const reload = () => go(currentView);

/* generic table builder: cols = [{h, f(row)}] */
function table(cols, rows, emptyMsg) {
  if (!rows.length) return `<div class="empty">${esc(emptyMsg)}</div>`;
  return `<table><thead><tr>${cols.map((c) => `<th>${c.h}</th>`).join('')}</tr></thead><tbody>` +
    rows.map((r) => `<tr>${cols.map((c) => `<td ${c.cls ? `class="${c.cls}"` : ''}>${c.f(r)}</td>`).join('')}</tr>`).join('') +
    '</tbody></table>';
}

/* ===================================================================
   VIEWS
   =================================================================== */
const views = {};

/* ---------- Dashboard ---------- */
views.dashboard = async () => {
  const [s, recent] = await Promise.all([api('/stats'), api('/recent')]);
  const stat = (n, label, hl) => `<div class="stat ${hl ? 'hl' : ''}"><b>${n}</b><span>${label}</span></div>`;
  $('view').innerHTML = `
    <div class="stats">
      ${stat(s.open_cases, 'Active cases', true)}
      ${stat(s.evidence, 'Evidence items')}
      ${stat(s.in_storage, 'In storage')}
      ${stat(s.out_of_storage, 'Out of storage')}
      ${stat(s.locations, 'Storage locations')}
      ${stat(s.users, 'Team members')}
    </div>
    <div class="panel"><h2>Latest custody activity</h2>
    ${table([
      { h: 'When', f: (r) => when(r.action_time) },
      { h: 'Evidence', f: (r) => `#${r.evidence_id} ${esc(r.item_type)}` },
      { h: 'Action', f: (r) => esc(r.action_type) },
      { h: 'From', f: (r) => esc(r.from_name || '-') },
      { h: 'To', f: (r) => esc(r.to_name || '-') },
      { h: 'Remarks', f: (r) => esc(r.remarks || '') },
    ], recent, 'No custody activity yet. Register evidence to start the chain of custody.')}</div>`;
};

/* ---------- Cases ---------- */
let caseSearch = '';
views.cases = async () => {
  const rows = await api('/cases?q=' + encodeURIComponent(caseSearch));
  casesCache = Object.fromEntries(rows.map((c) => [c.case_id, c]));
  $('view').innerHTML = `
    <div class="toolbar">
      <input id="case-q" type="search" placeholder="Search by case number or title" value="${esc(caseSearch)}">
      <span class="spacer"></span>
      <button class="btn primary" onclick="newCase()">New case</button>
    </div>
    <div class="panel">${table([
      { h: 'Case no.', f: (r) => `<strong>${esc(r.case_number)}</strong>` },
      { h: 'Title', f: (r) => esc(r.title) },
      { h: 'Reported', f: (r) => day(r.reported_date) },
      { h: 'Status', f: (r) => `<select onchange="setCaseStatus(${r.case_id}, this.value)">${opts(CASE_STATUS, r.status)}</select>` },
      { h: 'Evidence', f: (r) => r.evidence_count },
      { h: 'Created by', f: (r) => esc(r.created_by_name || '-') },
      { h: '', cls: 'actions', f: (r) => `<button class="btn small" onclick="showCase(${r.case_id})">Open</button>
         <button class="btn small danger" onclick="delCase(${r.case_id})">Delete</button>` },
    ], rows, 'No cases found. Select "New case" to register one.')}</div>`;
  $('case-q').onchange = (e) => { caseSearch = e.target.value; reload(); };
};

function newCase() {
  openForm('New case', [
    { name: 'case_number', label: 'Case number', required: true, placeholder: 'CASE-2026-002' },
    { name: 'reported_date', label: 'Reported date', type: 'date', required: true, value: today() },
    { name: 'title', label: 'Title', required: true },
    { name: 'status', label: 'Status', type: 'select', options: CASE_STATUS },
    { name: 'description', label: 'Description', type: 'textarea' },
  ], async (d) => { await api('/cases', 'POST', d); toast('Case created'); reload(); });
}
const setCaseStatus = (id, status) => run(() => api('/cases', 'PUT', { id, status }), 'Status updated');
const delCase = (id) => {
  if (!confirm('Delete this case with its notes and assignments? Cases that still have evidence cannot be deleted.')) return;
  run(async () => { await api('/cases?id=' + id, 'DELETE'); reload(); }, 'Case deleted');
};

/* case details: team (case_assignment) + notes (case_note) */
async function showCase(id) {
  const c = casesCache[id];
  const [team, notes, users] = await Promise.all([api('/assignments?case_id=' + id), api('/notes?case_id=' + id), api('/users')]);
  openModal(`${c.case_number} - ${c.title}`, `
    <p>${esc(c.description || 'No description.')}</p>
    <h3>Assigned team</h3>
    ${table([
      { h: 'Name', f: (r) => esc(r.name) },
      { h: 'Designation', f: (r) => esc(r.designation || '') },
      { h: 'Role on case', f: (r) => esc(r.assigned_role) },
      { h: 'Since', f: (r) => day(r.assigned_at) },
      { h: '', f: (r) => `<button class="btn small danger" onclick="unassign(${id}, ${r.user_id})">Remove</button>` },
    ], team, 'Nobody is assigned yet.')}
    <div class="inline-form">
      <label>Add person<select id="as-user">${opts(users.map((u) => ({ v: u.user_id, t: u.name })))}</select></label>
      <label>Role<input id="as-role" value="Investigator"></label>
      <button class="btn" onclick="assign(${id})">Assign</button>
    </div>
    <h3>Case notes</h3>
    ${notes.length ? notes.map((n) => `<div class="note">${esc(n.note_text)}<small>${esc(n.author || 'Unknown')} - ${when(n.created_at)}</small></div>`).join('') : '<p class="muted">No notes yet.</p>'}
    <div class="inline-form">
      <label>New note<textarea id="note-text" style="min-height:60px"></textarea></label>
      <button class="btn primary" onclick="addNote(${id})">Add note</button>
    </div>`);
}
const assign = (id) => run(async () => { await api('/assignments', 'POST', { case_id: id, user_id: $('as-user').value, assigned_role: $('as-role').value }); showCase(id); });
const unassign = (id, uid) => run(async () => { await api(`/assignments?case_id=${id}&user_id=${uid}`, 'DELETE'); showCase(id); });
const addNote = (id) => run(async () => { await api('/notes', 'POST', { case_id: id, note_text: $('note-text').value }); showCase(id); });

/* ---------- Evidence ---------- */
const evFilter = { q: '', case_id: '', status: '' };
views.evidence = async () => {
  const qs = new URLSearchParams(Object.entries(evFilter).filter(([, v]) => v)).toString();
  const [rows, cases] = await Promise.all([api('/evidence?' + qs), api('/cases')]);
  $('view').innerHTML = `
    <div class="toolbar">
      <input id="ev-q" type="search" placeholder="Search item, description or case no." value="${esc(evFilter.q)}">
      <select id="ev-case"><option value="">All cases</option>${opts(cases.map((c) => ({ v: c.case_id, t: c.case_number })), evFilter.case_id)}</select>
      <select id="ev-status"><option value="">All statuses</option>${opts(EV_STATUS, evFilter.status)}</select>
      <span class="spacer"></span>
      <button class="btn primary" onclick="newEvidence()">Add evidence</button>
    </div>
    <div class="panel">${table([
      { h: 'ID', f: (r) => '#' + r.evidence_id },
      { h: 'Item', f: (r) => `<strong>${esc(r.item_type)}</strong><br><span class="muted">${esc(r.description || '')}</span>` },
      { h: 'Case', f: (r) => esc(r.case_number) },
      { h: 'Location', f: (r) => esc(r.location_label || 'Not assigned') },
      { h: 'Status', f: (r) => `<select onchange="setEvStatus(${r.evidence_id}, this.value)">${opts(EV_STATUS, r.status)}</select>` },
      { h: 'Tags', f: (r) => (r.tags || '').split(', ').filter(Boolean).map((t) => `<span class="tag">${esc(t)}</span>`).join('') },
      { h: '', cls: 'actions', f: (r) => `<button class="btn small" onclick="moveEvidence(${r.evidence_id})">Move</button>
         <button class="btn small" onclick="history_(${r.evidence_id})">History</button>
         <button class="btn small" onclick="editTags(${r.evidence_id})">Tags</button>
         <button class="btn small danger" onclick="delEvidence(${r.evidence_id})">Delete</button>` },
    ], rows, 'No evidence matches. Change the filters or select "Add evidence".')}</div>`;
  $('ev-q').onchange = (e) => { evFilter.q = e.target.value; reload(); };
  $('ev-case').onchange = (e) => { evFilter.case_id = e.target.value; reload(); };
  $('ev-status').onchange = (e) => { evFilter.status = e.target.value; reload(); };
};

async function newEvidence() {
  const [cases, locs] = await Promise.all([api('/cases'), api('/locations')]);
  if (!cases.length) { toast('Create a case first, then add evidence to it.', true); return; }
  openForm('Add evidence', [
    { name: 'case_id', label: 'Case', type: 'select', required: true, options: cases.map((c) => ({ v: c.case_id, t: `${c.case_number} - ${c.title}` })) },
    { name: 'item_type', label: 'Item type', required: true, placeholder: 'Mobile phone, knife, documents...' },
    { name: 'seized_date', label: 'Seized date', type: 'date', required: true, value: today() },
    { name: 'item_condition', label: 'Condition', placeholder: 'Intact, damaged...' },
    { name: 'location_id', label: 'Storage location', type: 'select', optional: true, options: locs.map(locOpt) },
    { name: 'photo_path', label: 'Photo path or URL' },
    { name: 'description', label: 'Description', type: 'textarea' },
  ], async (d) => { await api('/evidence', 'POST', d); toast('Evidence registered'); reload(); });
}
const locOpt = (l) => ({ v: l.location_id, t: [l.room_number, l.cabinet_number, l.shelf_number, l.locker_number].filter(Boolean).join(' / ') });
const setEvStatus = (id, status) => run(() => api('/evidence', 'PUT', { id, status }), 'Status updated');
const delEvidence = (id) => {
  if (!confirm('Delete this evidence item and its custody history? This cannot be undone.')) return;
  run(async () => { await api('/evidence?id=' + id, 'DELETE'); reload(); }, 'Evidence deleted');
};

async function moveEvidence(id) {
  const [locs, users] = await Promise.all([api('/locations'), api('/users')]);
  openForm(`Move evidence #${id}`, [
    { name: 'movement_type', label: 'Movement type', type: 'select', options: MOVE_TYPES },
    { name: 'to_location_id', label: 'New location (leave empty to keep current)', type: 'select', optional: true, options: locs.map(locOpt) },
    { name: 'to_user_id', label: 'Handed to', type: 'select', optional: true, options: users.map((u) => ({ v: u.user_id, t: u.name })) },
    { name: 'reason', label: 'Reason' },
  ], async (d) => { await api('/evidence/move', 'POST', { ...d, evidence_id: id }); toast('Movement recorded'); reload(); }, 'Record movement');
}

async function history_(id) {
  const [custody, moves] = await Promise.all([api('/custody?evidence_id=' + id), api('/movements?evidence_id=' + id)]);
  openModal(`Evidence #${id} history`, `
    <h3>Chain of custody</h3>
    ${custody.length ? `<ul class="timeline">${custody.map((c) => `<li><strong>${esc(c.action_type)}</strong> - ${esc(c.from_name || 'Scene')} to ${esc(c.to_name || 'Storage')}
       <small>${when(c.action_time)}${c.remarks ? ' - ' + esc(c.remarks) : ''}</small></li>`).join('')}</ul>` : '<p class="muted">No custody records.</p>'}
    <h3>Location movements</h3>
    ${table([
      { h: 'Date', f: (r) => when(r.movement_date) },
      { h: 'Type', f: (r) => esc(r.movement_type) },
      { h: 'From', f: (r) => esc(r.from_location || '-') },
      { h: 'To', f: (r) => esc(r.to_location || '-') },
      { h: 'By', f: (r) => esc(r.from_user || '-') },
      { h: 'Reason', f: (r) => esc(r.reason || '') },
    ], moves, 'This item has not been moved yet.')}`);
}

async function editTags(id) {
  const [mine, all] = await Promise.all([api('/evidence/tags?evidence_id=' + id), api('/tags')]);
  openModal(`Tags for evidence #${id}`, `
    <p>${mine.length ? mine.map((t) => `<span class="tag">${esc(t.tag_name)} <a href="#" onclick="rmTag(${id},${t.tag_id});return false" aria-label="Remove tag">&times;</a></span>`).join('') : '<span class="muted">No tags yet.</span>'}</p>
    <div class="inline-form">
      <label>Add tag<select id="tag-pick">${opts(all.map((t) => ({ v: t.tag_id, t: t.tag_name })))}</select></label>
      <button class="btn primary" onclick="addTag(${id})">Add tag</button>
    </div>`);
}
const addTag = (id) => run(async () => { await api('/evidence/tag', 'POST', { evidence_id: id, tag_id: $('tag-pick').value }); editTags(id); });
const rmTag = (id, t) => run(async () => { await api(`/evidence/tag?evidence_id=${id}&tag_id=${t}`, 'DELETE'); editTags(id); });

/* ---------- Storage locations ---------- */
views.locations = async () => {
  const rows = await api('/locations');
  $('view').innerHTML = `
    <div class="toolbar"><span class="spacer"></span><button class="btn primary" onclick="newLocation()">Add location</button></div>
    <div class="panel">${table([
      { h: 'ID', f: (r) => r.location_id },
      { h: 'Room', f: (r) => esc(r.room_number) },
      { h: 'Cabinet', f: (r) => esc(r.cabinet_number || '-') },
      { h: 'Shelf', f: (r) => esc(r.shelf_number || '-') },
      { h: 'Locker', f: (r) => esc(r.locker_number || '-') },
      { h: 'Items stored', f: (r) => r.items_stored },
      { h: '', f: (r) => `<button class="btn small danger" onclick="delLocation(${r.location_id})">Delete</button>` },
    ], rows, 'No storage locations yet. Add the first room or locker.')}</div>`;
};
function newLocation() {
  openForm('Add storage location', [
    { name: 'room_number', label: 'Room', required: true },
    { name: 'cabinet_number', label: 'Cabinet' },
    { name: 'shelf_number', label: 'Shelf' },
    { name: 'locker_number', label: 'Locker' },
  ], async (d) => { await api('/locations', 'POST', d); toast('Location added'); reload(); });
}
const delLocation = (id) => {
  if (!confirm('Delete this location? Items stored here will show as "Not assigned".')) return;
  run(async () => { await api('/locations?id=' + id, 'DELETE'); reload(); }, 'Location deleted');
};

/* ---------- Tags ---------- */
views.tags = async () => {
  const rows = await api('/tags');
  $('view').innerHTML = `
    <div class="toolbar"><span class="spacer"></span><button class="btn primary" onclick="newTag()">Add tag</button></div>
    <div class="panel">${table([
      { h: 'Tag', f: (r) => `<span class="tag">${esc(r.tag_name)}</span>` },
      { h: 'Description', f: (r) => esc(r.description || '') },
      { h: 'Used on', f: (r) => r.used_on + ' item(s)' },
    ], rows, 'No tags yet. Tags help you flag fragile, biological or high-value items.')}</div>`;
};
function newTag() {
  openForm('Add tag', [
    { name: 'tag_name', label: 'Tag name', required: true },
    { name: 'description', label: 'Description' },
  ], async (d) => { await api('/tags', 'POST', d); toast('Tag added'); reload(); });
}

/* ---------- Team (users) ---------- */
views.users = async () => {
  const rows = await api('/users');
  $('view').innerHTML = `
    <div class="toolbar"><span class="spacer"></span>
      ${me.role === 'Admin' ? '<button class="btn primary" onclick="newUser()">Add team member</button>' : ''}</div>
    <div class="panel">${table([
      { h: 'Name', f: (r) => `<strong>${esc(r.name)}</strong>` },
      { h: 'Email', f: (r) => esc(r.email) },
      { h: 'Role', f: (r) => esc(r.role) },
      { h: 'Designation', f: (r) => esc(r.designation || '') },
      { h: 'Phone', f: (r) => esc(r.phone || '') },
    ], rows, 'No users.')}</div>`;
};
function newUser() {
  openForm('Add team member', [
    { name: 'name', label: 'Full name', required: true },
    { name: 'email', label: 'Email', type: 'email', required: true },
    { name: 'password', label: 'Temporary password', type: 'password', required: true },
    { name: 'role', label: 'Role', type: 'select', options: ROLES },
    { name: 'designation', label: 'Designation' },
    { name: 'phone', label: 'Phone' },
  ], async (d) => { await api('/users', 'POST', d); toast('Team member added'); reload(); });
}

/* ---------------- boot ---------------- */
start();

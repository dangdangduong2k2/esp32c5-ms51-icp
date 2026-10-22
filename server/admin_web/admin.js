'use strict';
const $ = id => document.getElementById(id);
const state = {csrf: '', user: '', page: 1, query: '', selected: null, detail: null, loading: false, pending: false, editing: null, epoch: 0, detailEpoch: 0};
function el(tag, cls, text) { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; }
function icon(name) { const n = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); n.setAttribute('aria-hidden', 'true'); const u = document.createElementNS(n.namespaceURI, 'use'); u.setAttribute('href', '#i-' + name); n.append(u); return n; }
function button(text, cls, action) { const n = el('button', cls, text); n.type = 'button'; n.addEventListener('click', action); return n; }
function message(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(message.timer); message.timer = setTimeout(() => $('toast').hidden = true, 5000); }
function showLogin() { state.epoch++; state.detailEpoch++; state.csrf = ''; state.selected = null; state.detail = null; state.user = ''; $('dashboard').hidden = true; $('login').hidden = false; $('users-body').replaceChildren(); $('detail').replaceChildren(); $('edit-dialog').close(); $('access-dialog').close(); $('permission-fields').replaceChildren(); $('edit-fields').replaceChildren(); $('login-password').value = ''; }
async function api(path, body) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), path.endsWith("/options") ? 45000 : 15000);
  try {
    const response = await fetch('api/' + path, {method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: body === undefined ? {} : {'Content-Type': 'application/json', 'X-CSRF-Token': state.csrf}, body: body === undefined ? undefined : JSON.stringify(body)});
    let data; try { data = await response.json(); } catch { throw new Error('Máy chủ trả về dữ liệu chưa hợp lệ. Hãy thử lại.'); }
    if (!response.ok) { if (response.status === 401 && path !== 'login') showLogin(); throw new Error(data.message || 'Không thể hoàn tất thao tác.'); }
    return data;
  } catch (error) { if (error.name === 'AbortError') throw new Error('Chưa nhận được phản hồi. Hãy làm mới để kiểm tra trước khi thử lại.'); if (error instanceof TypeError) throw new Error('Không kết nối được máy chủ. Kiểm tra kết nối Internet.'); throw error; }
  finally { clearTimeout(timer); }
}
function eye(input) { const b = button('', 'eye', () => { const visible = input.type === 'password'; input.type = visible ? 'text' : 'password'; b.setAttribute('aria-pressed', String(visible)); b.setAttribute('aria-label', visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'); }); b.append(icon('eye')); b.setAttribute('aria-label', 'Hiện mật khẩu'); b.setAttribute('aria-pressed', 'false'); return b; }
document.querySelectorAll('[data-eye]').forEach(b => b.addEventListener('click', () => { const input = $(b.dataset.eye), visible = input.type === 'password'; input.type = visible ? 'text' : 'password'; b.setAttribute('aria-pressed', String(visible)); b.setAttribute('aria-label', visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'); }));
function authenticated(data) { state.csrf = data.csrf; state.user = data.username; state.page = 1; state.query = ''; $('search').value = ''; $('admin-name').textContent = data.username; $('login').hidden = true; $('dashboard').hidden = false; $('detail').replaceChildren(el('div', 'detail-placeholder', 'Chọn một tài khoản để xem thiết bị đã ghép.')); refresh(); }
$('login-form').addEventListener('submit', async e => { e.preventDefault(); if (state.pending) return; state.pending = true; const submit = e.submitter; submit.disabled = true; $('login-error').textContent = ''; try { const data = await api('login', {username: $('login-name').value.trim(), password: $('login-password').value}); $('login-password').value = ''; authenticated(data); } catch (e) { $('login-error').textContent = e.message; } finally { state.pending = false; submit.disabled = false; } });
function renderUsers(data) {
  $('total-badge').textContent = data.total;
  const rows = data.users.map(user => {
    const tr = el('tr', state.selected === user.id ? 'selected' : '');
    const td = el('td'), name = button('', 'account-name', () => selectUser(user.id, true));
    name.append(el('span', 'avatar', user.username.slice(0, 2).toUpperCase()), el('span', '', user.username)); td.append(name); tr.append(td, el('td', '', user.device_count));
    const live = el('td'), count = el('span', 'online-count' + (user.online_count ? '' : ' muted'));
    count.append(el('i', 'status-dot' + (user.online_count ? '' : ' offline')), document.createTextNode(user.online_count + ' / ' + user.device_count)); live.append(count); tr.append(live);
    const tail = el('td'), b = button('›', 'row-arrow', () => selectUser(user.id, true)); b.setAttribute('aria-label', 'Xem ' + user.username); tail.append(b); tr.append(tail); tr.dataset.uid = user.id; return tr;
  });
  $('users-body').replaceChildren(...rows); $('empty').hidden = data.total !== 0;
  $('page-label').textContent = data.total ? `${(state.page - 1) * data.page_size + 1}–${Math.min(state.page * data.page_size, data.total)} trong ${data.total} tài khoản` : '0 tài khoản';
  $('prev-page').disabled = state.page <= 1; $('next-page').disabled = state.page * data.page_size >= data.total;
  for (const name of ['accounts', 'devices', 'online', 'offline']) $('count-' + name).textContent = data.summary[name];
  if (!data.summary.mqtt) {
    $('count-online').textContent = '—'; $('count-offline').textContent = '—';
    document.querySelectorAll('.online-count').forEach(n => { n.textContent = 'Chưa rõ'; n.classList.add('muted'); });
  }
  $('broker-label').textContent = data.summary.mqtt ? 'Dịch vụ thiết bị hoạt động' : 'Kết nối thiết bị gián đoạn';
  $('connection-error').hidden = data.summary.mqtt;
  if (!data.summary.mqtt) $('connection-error').textContent = 'Máy chủ đang mất kết nối MQTT. Trạng thái thiết bị tạm thời chưa xác minh được.';
}
async function refresh() {
  if (!state.csrf || state.loading) return;
  state.loading = true; const epoch = state.epoch, query = state.query, page = state.page; $('refresh').disabled = true;
  try {
    const data = await api(`users?q=${encodeURIComponent(query)}&page=${page}`);
    if (epoch !== state.epoch) return;
    if (query !== state.query || page !== state.page) return;
    if (data.total && (page - 1) * data.page_size >= data.total) { state.page = 1; return; }
    renderUsers(data);
    if (state.selected) await selectUser(state.selected, false);
    if (epoch === state.epoch && state.csrf) $('updated').textContent = 'Cập nhật lúc ' + new Date().toLocaleTimeString('vi-VN');
  } catch (e) { if (epoch !== state.epoch) return; $('connection-error').textContent = e.message; $('connection-error').hidden = false; $('updated').textContent = 'Chưa cập nhật được trạng thái'; $('count-online').textContent = '—'; $('count-offline').textContent = '—'; $('broker-label').textContent = 'Chưa xác minh được kết nối'; document.querySelectorAll('.online-count').forEach(n => { n.textContent = 'Chưa rõ'; n.classList.add('muted'); }); document.querySelectorAll('.device-status').forEach(n => n.replaceChildren(el('span', 'pill offline', 'Chưa cập nhật'))); }
  finally { state.loading = false; $('refresh').disabled = false; if (state.csrf && (query !== state.query || page !== state.page)) refresh(); }
}
function seen(value) { if (!value) return 'Chưa có tín hiệu'; return new Date(value * 1000).toLocaleString('vi-VN', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'}); }
function renderDetail(data) {
  state.detail = data;
  const top = el('div', 'detail-top'), title = el('div');
  title.append(el('h2', '', data.user.username), el('p', '', 'Thông tin tài khoản'));
  top.append(el('span', 'avatar', data.user.username.slice(0, 2).toUpperCase()), title, button('Chỉnh sửa', 'text-button', editUser));
  const heading = el('div', 'device-heading'); heading.append(el('h3', '', 'Thiết bị đã ghép'), el('span', '', data.devices.length + ' thiết bị'));
  const list = el('div', 'device-list');
  for (const device of data.devices) {
    const item = el('article', 'device-item'), title = el('div', 'device-title'), text = el('div'); text.append(el('strong', '', device.name), el('small', '', device.id));
    const open = button('', 'device-open', () => openAccess(data.user.id, device)); open.append(text);
    title.append(icon('device'), open, button('Đổi tên', 'text-button', () => editDevice(device)));
    const status = el('div', 'device-status'), pill = el('span', 'pill' + (device.online ? '' : ' offline'));
    pill.append(el('i', 'status-dot' + (device.online ? '' : ' offline')), document.createTextNode(!data.mqtt ? 'Chưa xác minh' : device.online ? 'Trực tuyến' : 'Ngoại tuyến'));
    status.append(pill, el('small', '', (device.online ? 'Tín hiệu: ' : 'Lần cuối: ') + seen(device.last_seen))); const accessButton = button('Cấp quyền  →', 'access-button', () => openAccess(data.user.id, device));
    item.append(title, status, accessButton); list.append(item);
  }
  if (!data.devices.length) { const empty = el('div', 'empty'); empty.append(icon('device'), el('h3', '', 'Chưa ghép thiết bị'), el('p', '', 'Thiết bị sẽ xuất hiện khi tài khoản này hoàn tất ghép nối trong app.')); list.append(empty); }
  $('detail').replaceChildren(top, heading, list, el('p', 'device-tip', 'Trạng thái dựa trên tín hiệu gần nhất gửi về máy chủ. Thiết bị không gửi tín hiệu quá 45 giây được hiển thị ngoại tuyến.'));
}
async function selectUser(uid, scroll) {
  const changed = state.selected !== uid;
  state.selected = uid; const epoch = ++state.detailEpoch;
  document.querySelectorAll('[data-uid]').forEach(n => n.classList.toggle('selected', n.dataset.uid === uid));
  if (changed) { state.detail = null; $('detail').replaceChildren(el('div', 'loading-note', 'Đang tải thiết bị…')); }
  try { const data = await api('users/' + encodeURIComponent(uid)); if (epoch !== state.detailEpoch || !state.csrf) return; renderDetail(data); if (scroll && innerWidth < 1150) $('detail').scrollIntoView({behavior: 'auto', block: 'start'}); }
  catch (e) { if (epoch !== state.detailEpoch) return; state.detail = null; $('detail').replaceChildren(el('div', 'empty', e.message)); }
}
function field(id, label, type, value, help, required = true, min = 1, max = 128) {
  const labelNode = el('label', '', label); labelNode.htmlFor = id;
  const input = el('input'); Object.assign(input, {id, name: id, type, value: value || '', required, minLength: min, maxLength: max});
  if (type === 'password') input.autocomplete = id === 'current-password' ? 'current-password' : 'new-password';
  else input.autocomplete = 'off';
  $('edit-fields').append(labelNode);
  if (type === 'password') { const wrap = el('div', 'password-field'); wrap.append(input, eye(input)); $('edit-fields').append(wrap); } else $('edit-fields').append(input);
  if (help) $('edit-fields').append(el('p', 'field-help', help));
}
function openEdit(title, description, editing, fields) { if (state.pending) return; state.editing = editing; $('edit-title').textContent = title; $('edit-description').textContent = description; $('edit-error').textContent = ''; $('edit-fields').replaceChildren(); fields(); $('edit-dialog').showModal(); }
function editUser() { if (!state.detail) return; const user = state.detail.user; openEdit('Chỉnh sửa tài khoản', 'Đổi tên đăng nhập hoặc đặt lại mật khẩu sẽ yêu cầu người dùng đăng nhập lại. Thiết bị đã ghép vẫn được giữ nguyên.', {kind: 'user', uid: user.id}, () => { field('new-username', 'Tên đăng nhập', 'text', user.username, '3–32 ký tự: chữ không dấu, số, dấu . _ -', true, 3, 32); field('new-password', 'Mật khẩu mới', 'password', '', 'Để trống nếu giữ mật khẩu. Mật khẩu mới từ 8 ký tự.', false, 8); field('confirm-password', 'Nhập lại mật khẩu mới', 'password', '', '', false, 8); }); }
function editDevice(device) { const uid = state.detail.user.id; openEdit('Đổi tên thiết bị', 'Tên mới sẽ được cập nhật trong danh sách thiết bị của tài khoản.', {kind: 'device', uid, device: device.id}, () => field('device-name', 'Tên thiết bị', 'text', device.name, 'Tối đa 40 ký tự.', true, 1, 40)); }
$('own-password').onclick = () => openEdit('Đổi mật khẩu admin', 'Sau khi đổi mật khẩu, hãy đăng nhập lại bằng mật khẩu mới. Các tài khoản trong app không bị ảnh hưởng.', {kind: 'admin'}, () => { field('current-password', 'Mật khẩu hiện tại', 'password', '', '', true, 8); field('new-password', 'Mật khẩu mới', 'password', '', 'Tối thiểu 12 ký tự.', true, 12); field('confirm-password', 'Nhập lại mật khẩu mới', 'password', '', '', true, 12); });
function closeEdit() { if (!state.pending) { $('edit-dialog').close(); $('edit-fields').replaceChildren(); state.editing = null; } }
$('close-dialog').onclick = closeEdit; $('cancel-edit').onclick = closeEdit;
$('edit-dialog').addEventListener('cancel', e => { e.preventDefault(); closeEdit(); });
$('edit-form').addEventListener('submit', async event => {
  event.preventDefault(); if (state.pending) return;
  const edit = {...state.editing}, password = $('new-password')?.value, confirmation = $('confirm-password')?.value;
  $('edit-error').textContent = '';
  if (edit.kind !== 'device' && password !== confirmation) { $('edit-error').textContent = 'Hai mật khẩu mới chưa trùng khớp.'; return; }
  state.pending = true; const controls = [...$('edit-form').querySelectorAll('button,input')]; controls.forEach(n => n.disabled = true);
  try {
    if (edit.kind === 'user') { const body = {username: $('new-username').value.trim()}; if (password) body.password = password; await api('users/' + edit.uid, body); }
    else if (edit.kind === 'device') await api('users/' + edit.uid + '/devices/' + encodeURIComponent(edit.device), {name: $('device-name').value.trim()});
    else await api('password', {current_password: $('current-password').value, password});
    $('edit-dialog').close(); $('edit-fields').replaceChildren(); state.editing = null;
    if (edit.kind === 'admin') { showLogin(); message('Đã đổi mật khẩu admin. Vui lòng đăng nhập lại.'); }
    else { message('Đã lưu thay đổi.'); refresh(); }
  } catch (e) { $('edit-error').textContent = e.message; }
  finally { state.pending = false; controls.forEach(n => n.disabled = false); }
});
$('logout').onclick = async () => { if (state.pending) return; state.pending = true; $('logout').disabled = true; try { await api('logout', {}); showLogin(); } catch (e) { message(e.message); } finally { state.pending = false; $('logout').disabled = false; } };
$('refresh').onclick = refresh;
$('search').addEventListener('input', () => { state.query = $('search').value.trim(); state.page = 1; clearTimeout(state.searchTimer); state.searchTimer = setTimeout(refresh, 300); });
$('prev-page').onclick = () => { if (state.page > 1) { state.page--; refresh(); } };
$('next-page').onclick = () => { state.page++; refresh(); };
setInterval(() => { if (!document.hidden && !$('edit-dialog').open && !$('access-dialog').open) refresh(); }, 15000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
api('session').then(authenticated).catch(() => showLogin());

let accessTarget = null, accessEpoch = 0, optionsRevision = null;
function accessBusy(busy) { state.pending=busy; $('access-dialog').querySelectorAll('button,input').forEach(n=>n.disabled=busy); for(const id of ['option-show-st','option-op-seconds','save-options']) $(id).disabled=busy || optionsRevision===null; }
function closeAccess() { if (state.pending) return; ++accessEpoch; accessTarget=null; $('access-dialog').close(); $('permission-fields').replaceChildren(); $('firmware-hex').value=''; }
$('close-access').onclick=closeAccess;
$('access-dialog').addEventListener('cancel',e=>{ e.preventDefault(); closeAccess(); });
function updateVisibleCount() { $('visible-count').textContent=$('permission-fields').querySelectorAll('input:checked').length+' / '+$('permission-fields').querySelectorAll('input').length+' mục được hiện'; }
function showAssigned(firmware,job) {
    $('assigned-firmware').replaceChildren();
    if (firmware) {
        $('assigned-firmware').append(el('strong','',firmware.filename),el('p','',firmware.id===firmware.installed?'Thiết bị đã cập nhật bản này.':'Đã cấp · Chờ người dùng cập nhật trong app.'));
        $('assigned-firmware').append(el('small','', 'Cấp lúc '+new Date(firmware.created*1000).toLocaleString('vi-VN')));
    } else $('assigned-firmware').append(el('p','','Chưa cấp bản cập nhật cho thiết bị này.'));
    if (job && firmware && job.release===firmware.id) $('assigned-firmware').append(el('p','',job.message));
    $('revoke-firmware').hidden=!firmware;
}
async function openAccess(uid,device) {
    if (state.pending) return;
    const epoch=++accessEpoch;
    accessTarget={uid,device:device.id,revision:0}; optionsRevision=null;
    $('option-show-st').checked=false; $('option-op-seconds').checked=false; $('options-status').textContent='Chưa đọc giá trị từ mạch.';
    $('access-device-name').textContent=device.name+' · '+device.id;
    $('access-error').textContent=''; $('permission-fields').replaceChildren(el('p','','Đang tải quyền…'));
    $('assigned-firmware').replaceChildren(); $('firmware-hex').value='';
    $('access-dialog').showModal(); accessBusy(true);
    try {
        const data=await api(`users/${uid}/devices/${device.id}/access`);
        if (epoch!==accessEpoch || !state.csrf) return;
        accessTarget.revision=data.revision;
        const groups={timing:'Thời gian hoạt động',modes:'Chế độ và hiển thị',ranges:'Giới hạn Min / Max',pin:'Mật khẩu bảo vệ'};
        const visible=new Set(data.visible); $('permission-fields').replaceChildren();
        for (const [group,title] of Object.entries(groups)) {
            const box=el('fieldset','permission-group'); box.append(el('legend','',title));
            for (const [key,field] of Object.entries(data.catalog)) if(field.group===group) {
                const label=el('label','permission-toggle'), input=el('input');
                input.type='checkbox'; input.value=key; input.checked=visible.has(key); input.onchange=updateVisibleCount;
                label.append(input,el('span','',field.label)); box.append(label);
            }
            $('permission-fields').append(box);
        }
        updateVisibleCount();showAssigned(data.firmware,data.job);
    } catch(e) { $('access-error').textContent=e.message; $('permission-fields').replaceChildren(); }
    finally { accessBusy(false); $('save-policy').disabled=$('permission-fields').querySelectorAll('input').length===0; }
}
$('show-all').onclick=()=>{ $('permission-fields').querySelectorAll('input').forEach(n=>n.checked=true);updateVisibleCount(); };
$('hide-all').onclick=()=>{ $('permission-fields').querySelectorAll('input').forEach(n=>n.checked=false);updateVisibleCount(); };
$('policy-form').onsubmit=async e=>{
    e.preventDefault(); if(state.pending||!accessTarget) return;
    const target={...accessTarget};accessBusy(true);$('access-error').textContent='';
    try {
        const visible=[...$('permission-fields').querySelectorAll('input:checked')].map(n=>n.value);
        const data=await api(`users/${target.uid}/devices/${target.device}/access`,{visible,revision:target.revision});
        accessTarget.revision=data.revision;message('Đã lưu quyền. App đang mở Cài đặt sẽ cập nhật trong khoảng 15 giây.');
    } catch(e) { $('access-error').textContent=e.message; } finally { accessBusy(false); }
};
$('firmware-form').onsubmit=async e=>{
    e.preventDefault();if(state.pending||!accessTarget) return;
    const file=$('firmware-hex').files[0], target={...accessTarget};$('access-error').textContent='';
    if(!file || !/\.hex$/i.test(file.name) || file.size>98304 || file.size===0) { $('access-error').textContent='Chọn tệp .hex có dữ liệu, tối đa 96 KiB.';return; }
    accessBusy(true);
    try {
        const response=await fetch(`api/users/${target.uid}/devices/${target.device}/firmware`,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/octet-stream','X-CSRF-Token':state.csrf,'X-Filename':file.name.replace(/[^A-Za-z0-9._ -]/g,'_')},body:file,signal:AbortSignal.timeout(20000)});
        const data=await response.json();if(!response.ok){if(response.status===401)showLogin();throw new Error(data.message||'Chưa cấp được firmware.');}
        showAssigned(data.firmware,null);$('firmware-hex').value='';message('Đã cấp firmware cho thiết bị.');
    }catch(e){$('access-error').textContent=e.name==='TimeoutError'?'Chưa nhận phản hồi. Đóng rồi mở lại để kiểm tra trước khi gửi lại.':e.message;}finally{accessBusy(false);}
};
$('revoke-firmware').onclick=async()=>{
    if(state.pending||!accessTarget||!confirm('Gỡ bản cập nhật đang cấp cho thiết bị này? Chương trình đang chạy trên thiết bị vẫn được giữ.'))return;
    const target={...accessTarget};accessBusy(true);$('access-error').textContent='';
    try{await api(`users/${target.uid}/devices/${target.device}/firmware/revoke`,{});showAssigned(null,null);message('Đã gỡ bản cập nhật.');}
    catch(e){$('access-error').textContent=e.message;}finally{accessBusy(false);}
};

$('read-options').onclick=async()=>{
    if(state.pending||!accessTarget)return;
    const target={...accessTarget},epoch=accessEpoch; optionsRevision=null;accessBusy(true);
    $('options-status').textContent='Đang đọc từ mạch…';
    try{
        const data=await api(`users/${target.uid}/devices/${target.device}/options`);
        if(epoch!==accessEpoch||!state.csrf)return;
        $('option-show-st').checked=data.values.showSt===1;
        $('option-op-seconds').checked=data.values.defrostOpSeconds===1;
        optionsRevision=data.revision; $('options-status').textContent='Đã đọc giá trị đang dùng trên mạch.';
    }catch(e){$('options-status').textContent=e.message;}finally{accessBusy(false);}
};
$('options-form').onsubmit=async e=>{
    e.preventDefault();if(state.pending||!accessTarget||optionsRevision===null)return;
    const target={...accessTarget}, revision=optionsRevision;
    const values={showSt:$('option-show-st').checked?1:0,defrostOpSeconds:$('option-op-seconds').checked?1:0};
    accessBusy(true);$('options-status').textContent='Đang lưu xuống mạch…';
    try{
        const data=await api(`users/${target.uid}/devices/${target.device}/options`,{values,revision});
        $('options-status').textContent=data.changed?'Mạch đã xác nhận lưu và đang khởi động lại.':'Giá trị không thay đổi.';
    }catch(e){$('options-status').textContent='Chưa xác nhận lưu. '+e.message+' Đọc lại từ mạch trước khi thử lại.';}
    finally{optionsRevision=null;accessBusy(false);}
};

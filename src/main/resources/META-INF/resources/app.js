const api = {
  status: '/api/status',
  user: '/api/user',
  customers: '/api/customers',
  write: '/api/write-test'
};

const punchlines = [
  "Ce user n'existait pas il y a quelques secondes.",
  "Et il n'existera plus dans quelques secondes.",
  "Même s'il fuite... il sera très vite inutile.",
  "L'application change de mot de passe sans jamais le connaître."
];

const el = {
  connectionState: document.getElementById('connectionState'),
  username: document.getElementById('username'),
  role: document.getElementById('role'),
  permissions: document.getElementById('permissions'),
  // ttlText: document.getElementById('ttlText'),
  timer: document.getElementById('timer'),
  ttlBar: document.getElementById('ttlBar'),
  punchline: document.getElementById('punchline'),
  sqlOutput: document.getElementById('sqlOutput'),
  usersOutput: document.getElementById('usersOutput'),
  usersList: document.getElementById('usersList')
};

let punchlineIndex = 0;

function updateConnectionState(connected) {
  el.connectionState.textContent = connected ? 'Connected' : 'Disconnected';
  const chip = el.connectionState.closest('.status-chip');
  if (!chip) {
    return;
  }
  chip.classList.toggle('connected', connected);
  chip.classList.toggle('disconnected', !connected);
}

function fmt(seconds) {
  const clamped = Math.max(0, Number(seconds || 0));
  const mm = String(Math.floor(clamped / 60)).padStart(2, '0');
  const ss = String(clamped % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

function print(target, data) {
  if (typeof data === 'string') {
    target.textContent = data;
    return;
  }
  target.textContent = JSON.stringify(data, null, 2);
}

function updateTtlBar(remaining, ttl) {
  const ratio = ttl > 0 ? Math.max(0, Math.min(1, remaining / ttl)) : 0;
  el.ttlBar.style.width = `${ratio * 100}%`;
  if (ratio < 0.15) {
    el.ttlBar.style.background = 'linear-gradient(90deg, #850000, #c64848)';
  } else if (ratio < 0.4) {
    el.ttlBar.style.background = 'linear-gradient(90deg, #df672d, #f1a06d)';
  } else {
    el.ttlBar.style.background = 'linear-gradient(90deg, #2d8f57, #57b573)';
  }
}

function paintUsers(users, currentUser, previousUser) {
  el.usersList.innerHTML = '';
  users.forEach((u) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = u;
    const badge = document.createElement('span');
    badge.className = 'badge';

    if (u === currentUser) {
      badge.textContent = 'ACTIVE';
      badge.classList.add('badge-live');
    } else if (previousUser && u === previousUser) {
      badge.textContent = 'OLD';
      badge.classList.add('badge-old');
    } else {
      badge.textContent = 'SYSTEM';
    }

    li.appendChild(name);
    li.appendChild(badge);
    el.usersList.appendChild(li);
  });
}

async function fetchJson(url, options = {}) {
  const res = await fetch(url, options);
  const contentType = res.headers.get('content-type') || '';
  if (!res.ok) {
    const errorBody = contentType.includes('application/json')
      ? JSON.stringify(await res.json())
      : await res.text();
    throw new Error(errorBody || `${res.status} ${res.statusText}`);
  }
  if (contentType.includes('application/json')) {
    return await res.json();
  }
  return await res.text();
}

async function refreshStatus() {
  try {
    const status = await fetchJson(api.status);
    const users = status.dbUsers || [];

    updateConnectionState(Boolean(status.connected));
    el.username.textContent = status.currentUser || '-';
    el.role.textContent = status.role || 'readonly';
    el.permissions.textContent = status.permissions || 'SELECT only';
//    el.ttlText.textContent = fmt(status.remainingSeconds);
    el.timer.textContent = fmt(status.remainingSeconds);

    updateTtlBar(status.remainingSeconds, status.ttlSeconds);
    paintUsers(users, status.currentUser, status.previousUser);

    if (status.remainingSeconds % 12 === 0) {
      punchlineIndex = (punchlineIndex + 1) % punchlines.length;
      el.punchline.textContent = punchlines[punchlineIndex];
    }

    el.usersOutput.textContent = `Users visibles (${new Date().toLocaleTimeString()}):\n` + users.join('\n');
  } catch (err) {
    updateConnectionState(false);
    el.sqlOutput.textContent = `Erreur API: ${err.message}`;
  }
}

async function runAction(name, task) {
  el.sqlOutput.textContent = `${name}...`;
  try {
    const result = await task();
    print(el.sqlOutput, result);
    await refreshStatus();
  } catch (err) {
    el.sqlOutput.textContent = `${name} ERROR\n${err.message}`;
  }
}

document.getElementById('btnUser').addEventListener('click', () => runAction('current_user', () => fetchJson(api.user)));
document.getElementById('btnCustomers').addEventListener('click', () => runAction('customers', () => fetchJson(api.customers)));
document.getElementById('btnWrite').addEventListener('click', () => runAction('write-test', () => fetchJson(api.write, { method: 'POST' })));

refreshStatus();
setInterval(refreshStatus, 1000);

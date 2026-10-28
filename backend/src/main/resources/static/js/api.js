'use strict';

const API = {
  async request(metodo, url, cuerpo) {
    const opciones = { method: metodo, headers: csrfHeaders(metodo), credentials: 'same-origin' };
    if (cuerpo !== undefined) {
      opciones.headers['Content-Type'] = 'application/json';
      opciones.body = JSON.stringify(cuerpo);
    }
    let resp;
    try {
      resp = await fetch(url, opciones);
    } catch (e) {
      throw new Error('No se pudo conectar con el servidor');
    }
    if (resp.status === 401 && !location.pathname.endsWith('login.html')) {
      location.href = '/login.html';
      throw new Error('Sesion expirada');
    }
    const texto = await resp.text();
    let datos = null;
    try { datos = texto ? JSON.parse(texto) : null; } catch { datos = texto; }
    if (!resp.ok) {
      const msg = (datos && datos.mensaje) ? datos.mensaje : ('Error HTTP ' + resp.status);
      throw new Error(msg);
    }
    return datos;
  },
  get(url) { return this.request('GET', url); },
  post(url, cuerpo) { return this.request('POST', url, cuerpo ?? {}); },
  put(url, cuerpo) { return this.request('PUT', url, cuerpo); },
  patch(url) { return this.request('PATCH', url); },

  async todosLosProductos(soloActivos) {
    const todos = [];
    for (let pagina = 0; ; pagina++) {
      const r = await this.get(`/api/productos?soloActivos=${soloActivos}&tamano=100&pagina=${pagina}`);
      todos.push(...r.contenido);
      if (pagina + 1 >= r.totalPaginas) return todos;
    }
  },
};

function csrfHeaders(metodo) {
  if (metodo === 'GET') return {};
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? { 'X-XSRF-TOKEN': decodeURIComponent(m[1]) } : {};
}

function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fecha(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleString('es-PE', { dateStyle: 'short', timeStyle: 'medium' });
}

function hora(iso) {
  return new Date(iso).toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' });
}

function num(v, dec = 2) {
  if (v === null || v === undefined || v === '') return '-';
  return Number(v).toLocaleString('es-PE', { maximumFractionDigits: dec });
}

function aviso(id, tipo, msg) {
  const el = document.getElementById(id);
  if (!el) return;
  el.className = 'aviso ' + tipo;
  el.textContent = msg;
  if (tipo === 'ok') setTimeout(() => { el.className = 'aviso'; }, 4000);
}

function estadoTabla(tbody, columnas, texto, esError) {
  tbody.innerHTML = `<tr><td colspan="${columnas}" class="estado ${esError ? 'error' : ''}">${esc(texto)}</td></tr>`;
}

function formJSON(form) {
  const datos = {};
  for (const [k, v] of new FormData(form).entries()) {
    if (v === '') continue;
    const campo = form.elements[k];
    datos[k] = (campo && campo.type === 'number') ? Number(v) : v;
  }
  return datos;
}

const VERSION_APP = '2.1.0';

const TAG_PRIORIDAD = { ALTA: 'bad', MEDIA: 'warn', BAJA: 'info' };
const TAG_ESTADO_ALERTA = { ABIERTA: 'bad', RECONOCIDA: 'warn', CERRADA: 'gris' };

let USUARIO = null;
const esAdmin = () => USUARIO && USUARIO.rol === 'ADMIN';

async function iniciarPagina(activa) {
  USUARIO = await API.get('/api/auth/me');
  const paginas = [
    ['index.html', 'Dashboard'], ['productos.html', 'Productos'], ['inventario.html', 'Inventario'],
    ['ventas.html', 'Ventas'], ['iot.html', 'Dispositivos IoT'], ['alertas.html', 'Alertas y reportes'], ['asistente.html', 'Asistente IA'],
  ];
  document.getElementById('sidebar').innerHTML = `
    <div class="logo">Smart<span>Stock</span> IoT</div>
    <div class="usuario">${esc(USUARIO.nombreCompleto)} · ${esc(USUARIO.rol)}</div>
    <nav class="nav">
      ${paginas.map(([href, txt]) => `<a href="/${href}" class="${href === activa ? 'activo' : ''}">${txt}</a>`).join('')}
      <a href="#" class="salir" id="btnSalir">Cerrar sesion</a>
    </nav>
    <div class="creditos">SmartStock IoT v${VERSION_APP} · Desarrollado por BR</div>`;
  document.getElementById('btnSalir').addEventListener('click', async (e) => {
    e.preventDefault();
    try { await API.post('/api/auth/logout'); } finally { location.href = '/login.html'; }
  });
  document.querySelectorAll('.solo-admin').forEach(el => { if (!esAdmin()) el.style.display = 'none'; });
  if (!window.IA && !document.getElementById('iaScript')) {
    const s = document.createElement('script');
    s.id = 'iaScript';
    s.src = '/js/ia.js';
    document.body.appendChild(s);
  }
}

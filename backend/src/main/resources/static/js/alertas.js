'use strict';

async function cargarAlertas() {
  const tb = document.getElementById('tbAlertas');
  estadoTabla(tb, 9, 'Cargando...');
  const params = new URLSearchParams();
  const est = document.getElementById('aEstado').value;
  const d = document.getElementById('aDesde').value;
  const h = document.getElementById('aHasta').value;
  if (est) params.set('estado', est);
  if (d) params.set('desde', d);
  if (h) params.set('hasta', h);
  try {
    const lista = await API.get('/api/alertas?' + params);
    if (lista.length === 0) return estadoTabla(tb, 9, 'Sin alertas en el periodo');
    tb.innerHTML = lista.map(a => `<tr>
      <td>${esc(a.tipo)}</td><td><span class="tag ${TAG_PRIORIDAD[a.prioridad]}">${esc(a.prioridad)}</span></td>
      <td><span class="tag ${TAG_ESTADO_ALERTA[a.estado]}">${esc(a.estado)}</span></td>
      <td style="white-space:normal;min-width:240px">${esc(a.mensaje)}</td><td class="num">${a.ocurrencias}</td>
      <td>${fecha(a.primeraVez)}</td><td>${fecha(a.ultimaVez)}</td><td>${esc(a.reconocidaPor ?? '-')}</td>
      <td>${a.estado === 'ABIERTA' ? `<button class="mini" data-reconocer="${a.id}">Reconocer</button>` : ''}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 9, e.message, true); }
}

async function cargarAuditoria() {
  const tb = document.getElementById('tbAud');
  try {
    const lista = await API.get('/api/auditoria');
    if (lista.length === 0) return estadoTabla(tb, 6, 'Sin registros');
    tb.innerHTML = lista.map(a => `<tr><td>${fecha(a.fecha)}</td><td>${esc(a.usuario)}</td><td>${esc(a.accion)}</td>
      <td>${esc(a.entidad)}</td><td class="num">${a.entidadId ?? ''}</td><td style="white-space:normal">${esc(a.detalle)}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 6, e.message, true); }
}

document.getElementById('btnAlertas').addEventListener('click', cargarAlertas);

document.getElementById('tbAlertas').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-reconocer]');
  if (!btn) return;
  try {
    await API.patch(`/api/alertas/${btn.dataset.reconocer}/reconocer`);
    aviso('aviso', 'ok', 'Alerta reconocida');
    cargarAlertas();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.querySelectorAll('[data-rep]').forEach(btn => btn.addEventListener('click', () => {
  const params = new URLSearchParams();
  const d = document.getElementById('rDesde').value;
  const h = document.getElementById('rHasta').value;
  if (d) params.set('desde', d);
  if (h) params.set('hasta', h);
  if (btn.dataset.rep === 'lecturas') params.set('sensorId', document.getElementById('rSensor').value);
  const u = document.getElementById('rUsuario').value.trim();
  if (btn.dataset.rep === 'movimientos' && u) params.set('usuario', u);
  const url = `/api/reportes/${btn.dataset.rep}.csv?${params}`;
  if (window.escritorio) window.escritorio.descargar(url);
  else window.location.href = url;
}));

(async () => {
  try { await iniciarPagina('alertas.html'); } catch { return; }
  cargarAlertas();
  if (esAdmin()) cargarAuditoria();
})();

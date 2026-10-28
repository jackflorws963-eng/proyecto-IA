'use strict';

let sensoresPeso = [];

async function cargarDiscrepancias() {
  const tb = document.getElementById('tbDisc');
  try {
    const lista = await API.get('/api/discrepancias?soloPendientes=' + document.getElementById('soloPendientes').value);
    if (lista.length === 0) return estadoTabla(tb, 9, 'No hay discrepancias');
    tb.innerHTML = lista.map(d => {
      const e = d.estimacion;
      const tag = { PENDIENTE: 'warn', APROBADA: 'ok', RECHAZADA: 'gris' }[d.estado];
      const acciones = d.estado === 'PENDIENTE'
        ? (esAdmin() ? `<button class="mini" data-aprobar="${d.id}">Aprobar</button>
                        <button class="sec mini" data-rechazar="${d.id}">Rechazar</button>` : '<span class="muted">Espera a un admin</span>')
        : `${esc(d.revisadoPor)} · ${esc(d.comentario)}`;
      return `<tr><td>${fecha(d.fecha)}</td><td>${esc(e.producto)}</td><td>${esc(e.ubicacion)}</td>
        <td class="num">${num(e.pesoNetoG, 1)}</td><td class="num">${num(e.cantidadEstimada)}</td>
        <td class="num">${e.cantidadRegistrada}</td><td class="num">${num(e.diferenciaPct)}</td>
        <td><span class="tag ${tag}">${esc(d.estado)}</span></td><td style="white-space:normal">${acciones}</td></tr>`;
    }).join('');
  } catch (e) { estadoTabla(tb, 9, e.message, true); }
}

async function cargarEstimaciones() {
  const tb = document.getElementById('tbEst');
  const id = document.getElementById('selSensorEst').value;
  if (!id) return estadoTabla(tb, 6, 'No hay sensores de peso con lecturas');
  try {
    const lista = await API.get('/api/estimaciones?sensorId=' + id);
    if (lista.length === 0) return estadoTabla(tb, 6, 'Sin estimaciones todavía');
    tb.innerHTML = lista.map(e => `<tr><td>${fecha(e.fecha)}</td><td>${esc(e.producto)}</td>
      <td class="num">${num(e.pesoNetoG, 1)}</td><td class="num">${num(e.cantidadEstimada)}</td>
      <td class="num">${e.cantidadRegistrada}</td><td class="num">${num(e.diferenciaPct)}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 6, e.message, true); }
}

async function cargarSensoresPeso() {
  const ultimas = await API.get('/api/dashboard/ultimas-lecturas');
  sensoresPeso = ultimas.filter(l => l.tipo === 'PESO');
  document.getElementById('selSensorEst').innerHTML =
    sensoresPeso.map(s => `<option value="${s.sensorId}">#${s.sensorId} · ${esc(s.dispositivo)}</option>`).join('');
}

async function cargarAdmin() {
  const [disps, sensores, ubs, prods] = await Promise.all([
    API.get('/api/dispositivos'), API.get('/api/sensores'), API.get('/api/ubicaciones'),
    API.todosLosProductos(true),
  ]);
  document.getElementById('selUbic').innerHTML = ubs.filter(u => u.activo).map(u => `<option value="${u.id}">${esc(u.nombre)}</option>`).join('');
  document.getElementById('selDisp').innerHTML = disps.map(d => `<option value="${d.id}">${esc(d.codigo)}</option>`).join('');
  document.getElementById('selProdSensor').innerHTML = prods.filter(p => p.pesoUnitarioG)
    .map(p => `<option value="${p.id}">${esc(p.codigo)} - ${esc(p.nombre)} (${num(p.pesoUnitarioG, 3)} g)</option>`).join('');

  document.getElementById('tbDisp').innerHTML = disps.length === 0 ? '<tr><td colspan="5" class="estado">Sin dispositivos</td></tr>' :
    disps.map(d => `<tr><td>${esc(d.codigo)}<div class="muted">${esc(d.nombre)}</div></td><td>${esc(d.ubicacion)}</td>
      <td>${!d.activo ? '<span class="tag gris">Inactivo</span>' : d.enLinea ? '<span class="tag ok">En línea</span>' : '<span class="tag bad">Sin conexión</span>'}
        <div class="muted">${fecha(d.ultimoContacto)}</div></td>
      <td><select class="ventilador" data-vent="${d.id}" style="min-width:110px">
          ${['AUTO', 'ENCENDIDO', 'APAGADO'].map(m => `<option ${m === d.ventiladorModo ? 'selected' : ''}>${m}</option>`).join('')}
        </select>
        <div class="muted">${d.ventiladorEncendido ? 'Encendido' : 'Apagado'}</div></td>
      <td><button class="sec mini" data-clave="${d.id}">Nueva clave</button>
          <button class="sec mini" data-disp="${d.id}" data-activo="${!d.activo}">${d.activo ? 'Desactivar' : 'Activar'}</button></td></tr>`).join('');

  document.getElementById('tbSensores').innerHTML = sensores.map(s => `<tr><td>${s.id}</td><td>${esc(s.dispositivo)}</td>
    <td>${esc(s.tipo)}</td><td>${esc(s.producto ?? '-')}</td>
    <td><span class="tag ${s.activo ? 'ok' : 'gris'}">${s.activo ? 'Activo' : 'Inactivo'}</span></td>
    <td><button class="sec mini" data-sensor="${s.id}" data-activo="${!s.activo}">${s.activo ? 'Desactivar' : 'Activar'}</button></td></tr>`).join('');

  const peso = sensores.filter(s => s.tipo === 'PESO');
  document.getElementById('selSensorCal').innerHTML = peso.map(s => `<option value="${s.id}">#${s.id} · ${esc(s.dispositivo)} · ${esc(s.producto)}</option>`).join('');
  await cargarCalibraciones();
}

async function cargarCalibraciones() {
  const id = document.getElementById('selSensorCal').value;
  const tb = document.getElementById('tbCal');
  if (!id) return estadoTabla(tb, 6, 'No hay sensores de peso');
  const lista = await API.get('/api/calibraciones?sensorId=' + id);
  if (lista.length === 0) return estadoTabla(tb, 6, 'Sin calibraciones');
  const vigente = lista.find(c => c.vigente);
  if (vigente) document.querySelector('#formCal [name=factor]').value = vigente.factor;
  tb.innerHTML = lista.map(c => `<tr><td>${fecha(c.fecha)}</td><td class="num">${num(c.factor, 6)}</td><td class="num">${num(c.taraG, 3)}</td>
    <td class="num">${num(c.pesoPatronG, 3)}</td><td>${esc(c.usuario)}</td>
    <td>${c.vigente ? '<span class="tag ok">Vigente</span>' : ''}</td></tr>`).join('');
}

function ajustarFormSensor() {
  const esPeso = document.getElementById('selTipo').value === 'PESO';
  document.getElementById('lblProdSensor').style.display = esPeso ? '' : 'none';
}

document.getElementById('soloPendientes').addEventListener('change', cargarDiscrepancias);
document.getElementById('selSensorEst').addEventListener('change', cargarEstimaciones);
document.getElementById('selTipo').addEventListener('change', ajustarFormSensor);
document.getElementById('selSensorCal').addEventListener('change', () => cargarCalibraciones().catch(e => aviso('aviso', 'error', e.message)));

document.getElementById('tbDisc').addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const id = btn.dataset.aprobar || btn.dataset.rechazar;
  const accion = btn.dataset.aprobar ? 'aprobar' : 'rechazar';
  const comentario = prompt(accion === 'aprobar'
    ? 'Comentario (ej.: conteo físico confirma la cantidad estimada):'
    : 'Motivo del rechazo (ej.: había un objeto extra en la balanza):');
  if (!comentario || !comentario.trim()) return;
  try {
    await API.post(`/api/discrepancias/${id}/${accion}`, { comentario });
    aviso('aviso', 'ok', accion === 'aprobar' ? 'Discrepancia aprobada: stock ajustado' : 'Discrepancia rechazada');
    cargarDiscrepancias();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('formDisp').addEventListener('submit', async (e) => {
  e.preventDefault();
  const datos = formJSON(e.target);
  datos.ubicacionId = Number(datos.ubicacionId);
  try {
    const r = await API.post('/api/dispositivos', datos);
    aviso('credencial', 'info', `Clave de ${r.dispositivo.codigo}: ${r.apiKey}  —  ${r.aviso}`);
    e.target.reset();
    cargarAdmin();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('tbDisp').addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  try {
    if (btn.dataset.clave) {
      if (!confirm('La clave actual dejará de funcionar. ¿Generar una nueva?')) return;
      const r = await API.post(`/api/dispositivos/${btn.dataset.clave}/regenerar-clave`);
      aviso('credencial', 'info', `Nueva clave de ${r.dispositivo.codigo}: ${r.apiKey}  —  ${r.aviso}`);
    } else if (btn.dataset.disp) {
      await API.patch(`/api/dispositivos/${btn.dataset.disp}/estado?activo=${btn.dataset.activo}`);
    }
    cargarAdmin();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('tbDisp').addEventListener('change', async (e) => {
  const sel = e.target.closest('select[data-vent]');
  if (!sel) return;
  try {
    const d = await API.patch(`/api/dispositivos/${sel.dataset.vent}/ventilador?modo=${sel.value}`);
    aviso('aviso', 'ok', `Ventilador de ${d.codigo}: ${d.ventiladorModo} (${d.ventiladorEncendido ? 'encendido' : 'apagado'})`);
  } catch (err) { aviso('aviso', 'error', err.message); }
  cargarAdmin();
});

document.getElementById('formSensor').addEventListener('submit', async (e) => {
  e.preventDefault();
  const datos = formJSON(e.target);
  datos.dispositivoId = Number(datos.dispositivoId);
  if (datos.tipo === 'PESO') datos.productoId = Number(datos.productoId); else delete datos.productoId;
  try {
    await API.post('/api/sensores', datos);
    aviso('aviso', 'ok', 'Sensor agregado');
    cargarAdmin();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('tbSensores').addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  try {
    await API.patch(`/api/sensores/${btn.dataset.sensor}?activo=${btn.dataset.activo}`);
    cargarAdmin();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('btnTara').addEventListener('click', async () => {
  const id = Number(document.getElementById('selSensorCal').value);
  try {
    const ultimas = await API.get('/api/dashboard/ultimas-lecturas');
    const l = ultimas.find(x => x.sensorId === id);
    if (!l) return aviso('aviso', 'error', 'Ese sensor aún no envía lecturas');
    document.getElementById('taraG').value = Math.max(0, Number(l.valor)).toFixed(3);
    aviso('aviso', 'ok', `Tara tomada de la lectura de ${fecha(l.medidoEn)}`);
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('formCal').addEventListener('submit', async (e) => {
  e.preventDefault();
  const datos = formJSON(e.target);
  datos.sensorId = Number(datos.sensorId);
  try {
    await API.post('/api/calibraciones', datos);
    aviso('aviso', 'ok', 'Calibración guardada');
    cargarCalibraciones();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

(async () => {
  try { await iniciarPagina('iot.html'); } catch { return; }
  ajustarFormSensor();
  try {
    await cargarSensoresPeso();
    await Promise.all([cargarDiscrepancias(), cargarEstimaciones()]);
    if (esAdmin()) await cargarAdmin();
  } catch (e) { aviso('aviso', 'error', e.message); }
  setInterval(() => { cargarDiscrepancias(); cargarEstimaciones(); }, 10000);
})();

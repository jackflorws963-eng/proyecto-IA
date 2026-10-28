'use strict';

let chat = null;
const anunciados = new Set();
let primeraVez = true;

async function cargarEstado() {
  const cont = document.getElementById('estadoIa');
  try {
    const e = await API.get('/api/ia/estado');
    cont.innerHTML = `
      <div class="kpi ${e.modeloDisponible ? 'ok' : ''}"><div class="l">Motor</div>
        <div class="v" style="font-size:18px">${e.modeloDisponible ? 'IA local' : 'Motor propio'}</div>
        <div class="muted" style="font-size:12px">${esc(e.modeloDisponible ? e.modelo : e.detalle)}</div></div>
      <div class="kpi"><div class="l">Internet</div><div class="v" style="font-size:18px">No necesita</div>
        <div class="muted" style="font-size:12px">todo se procesa en esta PC</div></div>
      <div class="kpi"><div class="l">Frases aprendidas</div><div class="v">${e.frasesAprendidas}</div></div>
      <div class="kpi"><div class="l">Recuerdos</div><div class="v">${e.recuerdos}</div></div>
      <div class="kpi"><div class="l">Herramientas</div><div class="v">${e.herramientas}</div>
        <div class="muted" style="font-size:12px">acciones sobre el programa</div></div>`;
  } catch (err) { cont.innerHTML = `<div class="estado error">${esc(err.message)}</div>`; }
}

async function cargarPronostico() {
  const tb = document.getElementById('tbPronostico');
  try {
    const l = (await API.get('/api/ia/pronostico')).filter(p => p.diasRestantes !== null).slice(0, 8);
    if (l.length === 0) return estadoTabla(tb, 6, 'Aún sin salidas: el pronóstico aprende con cada venta');
    const TAG = { ALTA: 'ok', MEDIA: 'warn', BAJA: 'gris' };
    tb.innerHTML = l.map(p => `<tr>
      <td>${esc(p.nombre)}</td><td class="num">${p.stock}</td><td class="num">${num(p.demandaDiaria)}</td>
      <td class="num">${p.diasRestantes <= 3 ? `<span class="tag bad">${p.diasRestantes} d</span>` : p.diasRestantes + ' d'}</td>
      <td class="num">${p.sugeridoReponer || '-'}</td><td><span class="tag ${TAG[p.confianza]}">${esc(p.confianza)}</span></td></tr>`).join('');
  } catch (err) { estadoTabla(tb, 6, err.message, true); }
}

async function cargarMemoria() {
  const cont = document.getElementById('memoria');
  try {
    const l = await API.get('/api/ia/memoria');
    cont.innerHTML = l.length === 0 ? '<div class="estado">Aún no le enseñaste nada. Dile «recuerda que…» o usa el formulario.</div>'
      : l.map(r => `<div class="recuerdo">
          <span class="tag ${r.tipo === 'PREFERENCIA' ? 'info' : 'gris'}">${r.tipo === 'PREFERENCIA' ? 'preferencia' : 'dato'}</span>
          <span style="flex:1">${esc(r.texto)}</span>
          <span class="muted" title="veces que lo usó">${r.usos}×</span>
          <button class="sec mini" data-olvidar="${r.id}" title="Olvidar">✕</button></div>`).join('');
  } catch (err) { cont.innerHTML = `<div class="estado error">${esc(err.message)}</div>`; }
}

document.getElementById('memoria').addEventListener('click', async (e) => {
  const id = e.target.dataset.olvidar;
  if (!id) return;
  try { await API.request('DELETE', '/api/ia/memoria/' + id); } catch (err) { aviso('aviso', 'error', err.message); }
  refrescar();
});

document.getElementById('formRecordar').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await API.post('/api/ia/memoria', { texto: e.target.elements.texto.value, preferencia: e.target.elements.preferencia.checked });
    e.target.reset();
    aviso('aviso', 'ok', 'Aprendido');
    refrescar();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

async function cargarDiagnostico() {
  const cont = document.getElementById('problemas');
  let d;
  try {
    d = await API.get('/api/asistente/diagnostico');
  } catch (err) {
    cont.innerHTML = `<div class="estado error">${esc(err.message)}</div>`;
    return;
  }
  document.getElementById('resumen').textContent = d.resumen;
  cont.innerHTML = d.problemas.length === 0 ? '<div class="estado">Sin problemas activos</div>' :
    d.problemas.map(p => `
      <div class="problema ${TAG_PRIORIDAD[p.gravedad]}">
        <div><span class="tag ${TAG_PRIORIDAD[p.gravedad]}">${esc(p.gravedad)}</span>
             <span class="tag gris">${esc(p.tipo)}</span></div>
        <div class="titulo">${esc(p.titulo)}</div>
        <div class="muted">${esc(p.sugerencia)}</div>
        ${p.acciones.length ? `<div class="acciones">${p.acciones.map(a =>
          `<button class="sec mini" data-accion="${esc(a.codigo)}">${esc(a.descripcion)}</button>`).join(' ')}</div>` : ''}
      </div>`).join('');

  const nuevos = d.problemas.filter(p => !anunciados.has(p.id) && p.gravedad !== 'BAJA');
  d.problemas.forEach(p => anunciados.add(p.id));
  if (primeraVez) { primeraVez = false; return; }
  if (nuevos.length && chat) {
    const extra = nuevos.length > 1 ? ` Hay ${nuevos.length - 1} problema(s) nuevo(s) más.` : '';
    chat.decir(`Atención: ${nuevos[0].titulo}. ${nuevos[0].sugerencia}${extra}`);
  }
}

document.getElementById('problemas').addEventListener('click', async (e) => {
  const b = e.target.closest('button[data-accion]');
  if (!b) return;
  b.disabled = true;
  try {
    const r = await API.post('/api/asistente/ejecutar', { acciones: [b.dataset.accion] });
    chat.decir(r.texto);
  } catch (err) { aviso('aviso', 'error', err.message); }
  cargarDiagnostico();
});

document.getElementById('btnReparar').addEventListener('click', () => chat.enviar('Repara los problemas'));

document.getElementById('btnReiniciar').addEventListener('click', async () => {
  await API.post('/api/ia/reiniciar');
  location.reload();
});

function pintarBotonVoz() {
  document.getElementById('btnVoz').textContent = IA.Voz.activa() ? 'Voz: activada' : 'Voz: desactivada';
}
document.getElementById('btnVoz').addEventListener('click', () => { IA.Voz.alternar(); pintarBotonVoz(); });

function refrescar() {
  cargarEstado();
  cargarMemoria();
  cargarPronostico();
  cargarDiagnostico();
}

(async () => {
  try { await iniciarPagina('asistente.html'); } catch { return; }
  pintarBotonVoz();
  chat = IA.crearChat(document.getElementById('chatPagina'), {
    sugerencias: ['¿Qué errores hay?', '¿Qué productos se van a agotar?', '¿Cuánto vendimos hoy?',
      'Registra una entrada de 10 P001', 'Abre ventas', '¿Qué recuerdas?', 'Ayuda'],
    bienvenida: 'Hola, soy el asistente de SmartStock. Funciono sin internet, puedo consultar y operar todo el programa y aprendo de lo que me enseñes. ¿En qué te ayudo?',
    alCambiar: () => { cargarEstado(); cargarMemoria(); cargarPronostico(); },
  });
  refrescar();
  setInterval(cargarDiagnostico, 20000);
})();

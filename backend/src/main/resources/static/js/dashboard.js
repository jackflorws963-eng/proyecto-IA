'use strict';

const graficos = {};

function graficoLinea(id, color) {
  if (typeof Chart === 'undefined') return null;   // sin internet no carga Chart.js: el resto sigue funcionando
  return new Chart(document.getElementById(id), {
    type: 'line',
    data: { labels: [], datasets: [{ data: [], borderColor: color, backgroundColor: color + '22', fill: true, pointRadius: 0, tension: .25, borderWidth: 2 }] },
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      plugins: { legend: { display: false } },
      scales: { x: { ticks: { maxTicksLimit: 6 } } },
    },
  });
}

function actualizarSerie(chart, puntos) {
  if (!chart) return;
  chart.data.labels = puntos.map(p => hora(p.t));
  chart.data.datasets[0].data = puntos.map(p => Number(p.v));
  chart.update();
}

async function cargarKpis() {
  const r = await API.get('/api/dashboard/resumen');
  const kpi = (l, v, clase = '') => `<div class="kpi ${clase}"><div class="l">${l}</div><div class="v">${v}</div></div>`;
  document.getElementById('kpis').innerHTML =
    kpi('Productos activos', r.productosActivos) +
    kpi('Bajo stock mínimo', r.productosBajoMinimo, r.productosBajoMinimo > 0 ? 'alerta' : 'ok') +
    kpi('Alertas activas', r.alertasActivas, r.alertasActivas > 0 ? 'alerta' : 'ok') +
    kpi('Discrepancias pendientes', r.discrepanciasPendientes, r.discrepanciasPendientes > 0 ? 'alerta' : 'ok') +
    kpi('Dispositivos en línea', `${r.dispositivosEnLinea} / ${r.dispositivosTotal}`, r.dispositivosEnLinea < r.dispositivosTotal ? 'alerta' : 'ok') +
    kpi('Lecturas última hora', r.lecturasUltimaHora);
}

async function cargarLecturasYSeries() {
  const ultimas = await API.get('/api/dashboard/ultimas-lecturas');
  const tb = document.getElementById('tbLecturas');
  if (ultimas.length === 0) estadoTabla(tb, 5, 'Aún no se reciben lecturas de los sensores');
  else tb.innerHTML = ultimas.map(l => `<tr>
      <td>${esc(l.dispositivo)}</td><td>${esc(l.tipo)}</td><td class="num">${num(l.valor)}</td>
      <td>${fecha(l.medidoEn)}</td>
      <td><span class="tag ${l.estado === 'VALIDA' ? 'ok' : 'bad'}">${esc(l.estado)}</span></td></tr>`).join('');

  const horas = document.getElementById('horas').value;
  const mapa = { TEMPERATURA: graficos.temp, HUMEDAD: graficos.hum, PESO: graficos.peso };
  for (const l of ultimas) {
    const puntos = await API.get(`/api/lecturas/serie?sensorId=${l.sensorId}&horas=${horas}`);
    actualizarSerie(mapa[l.tipo], puntos);
  }
}

async function cargarDispositivos() {
  const lista = await API.get('/api/dashboard/dispositivos');
  const tb = document.getElementById('tbDispositivos');
  if (lista.length === 0) return estadoTabla(tb, 4, 'No hay dispositivos registrados');
  tb.innerHTML = lista.map(d => `<tr>
      <td>${esc(d.codigo)}</td><td>${esc(d.ubicacion)}</td><td>${fecha(d.ultimoContacto)}</td>
      <td>${!d.activo ? '<span class="tag gris">Inactivo</span>'
          : d.enLinea ? '<span class="tag ok">En línea</span>' : '<span class="tag bad">Sin conexión</span>'}</td></tr>`).join('');
}

async function cargarAlertas() {
  const lista = await API.get('/api/dashboard/alertas');
  const tb = document.getElementById('tbAlertas');
  if (lista.length === 0) return estadoTabla(tb, 4, 'Sin alertas activas');
  tb.innerHTML = lista.map(a => `<tr>
      <td><span class="tag ${TAG_PRIORIDAD[a.prioridad]}">${esc(a.prioridad)}</span></td>
      <td style="white-space:normal">${esc(a.mensaje)}</td><td class="num">${a.ocurrencias}</td>
      <td>${fecha(a.ultimaVez)}</td></tr>`).join('');
}

async function cargarStock() {
  if (!graficos.stock) return;
  const inv = await API.get('/api/inventario');
  const porProducto = {};
  const minimos = {};
  for (const i of inv) {
    porProducto[i.productoCodigo] = (porProducto[i.productoCodigo] || 0) + i.cantidad;
    minimos[i.productoCodigo] = i.stockMinimo;
  }
  const codigos = Object.keys(porProducto);
  graficos.stock.data.labels = codigos;
  graficos.stock.data.datasets[0].data = codigos.map(c => porProducto[c]);
  graficos.stock.data.datasets[1].data = codigos.map(c => minimos[c]);
  graficos.stock.update();
}

async function refrescar() {
  try {
    await Promise.all([cargarKpis(), cargarLecturasYSeries(), cargarDispositivos(), cargarAlertas(), cargarStock()]);
    document.getElementById('ultimaAct').textContent = 'actualizado ' + new Date().toLocaleTimeString('es-PE');
    document.getElementById('aviso').className = 'aviso';
  } catch (e) {
    aviso('aviso', 'error', 'Error al actualizar: ' + e.message);
  }
}

(async () => {
  try {
    await iniciarPagina('index.html');
  } catch (e) { return; }
  graficos.temp = graficoLinea('chTemp', '#dc2626');
  graficos.hum = graficoLinea('chHum', '#2563eb');
  graficos.peso = graficoLinea('chPeso', '#0f766e');
  if (typeof Chart !== 'undefined') {
    graficos.stock = new Chart(document.getElementById('chStock'), {
      type: 'bar',
      data: { labels: [], datasets: [
        { label: 'Stock', data: [], backgroundColor: '#0f766e' },
        { label: 'Mínimo', data: [], backgroundColor: '#f59e0b' }] },
      options: { responsive: true, maintainAspectRatio: false, animation: false },
    });
  } else {
    aviso('aviso', 'error', 'No se pudo cargar la librería de gráficos (sin internet). Las tablas siguen funcionando.');
  }
  document.getElementById('horas').addEventListener('change', refrescar);
  await refrescar();
  setInterval(refrescar, 5000);
})();

'use strict';

// Punto de venta. El precio que se cobra lo fija el servidor (precio del producto al momento
// de la venta); aqui solo se muestra para que el vendedor vea el total antes de cobrar.

const TAG_VENTA = { EMITIDA: 'ok', ANULADA: 'bad' };
const NOMBRE_COMPROBANTE = { BOLETA: 'Boleta de venta', FACTURA: 'Factura', NOTA_VENTA: 'Nota de venta' };
const BOLETA_CON_DOCUMENTO = 700;   // igual que VentaService.BOLETA_CON_DOCUMENTO

let productos = [];                 // productos activos
let disponibles = new Map();        // productoId -> stock en la ubicacion elegida
const carrito = new Map();          // productoId -> cantidad

const soles = v => 'S/ ' + Number(v ?? 0).toLocaleString('es-PE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const $ = id => document.getElementById(id);

// ---------------------------------------------------------------- carrito

async function cargarDisponibles() {
  const ub = $('selUbicacion').value;
  disponibles = new Map();
  if (!ub) return;
  const lista = await API.get('/api/inventario?ubicacionId=' + ub);
  lista.forEach(i => disponibles.set(i.productoId, i.cantidad));
}

function productoDe(texto) {
  const t = texto.trim().toLowerCase();
  if (!t) return null;
  // El datalist completa "CODIGO - Nombre"; tambien se acepta solo el codigo
  return productos.find(p => `${p.codigo} - ${p.nombre}`.toLowerCase() === t)
      || productos.find(p => p.codigo.toLowerCase() === t)
      || null;
}

function totalCarrito() {
  let total = 0;
  for (const [id, cant] of carrito) {
    const p = productos.find(x => x.id === id);
    if (p) total += Number(p.precio) * cant;
  }
  return Math.round(total * 100) / 100;
}

function pintarCarrito() {
  const tb = $('tbCarrito');
  if (carrito.size === 0) {
    estadoTabla(tb, 7, 'Agregue productos para iniciar la venta');
  } else {
    tb.innerHTML = [...carrito].map(([id, cant]) => {
      const p = productos.find(x => x.id === id);
      const disp = disponibles.get(id) ?? 0;
      const falta = cant > disp;
      return `<tr>
        <td>${esc(p.codigo)}</td><td>${esc(p.nombre)}</td>
        <td class="num">${falta ? `<span class="tag bad">${disp}</span>` : disp}</td>
        <td class="num">${soles(p.precio)}</td>
        <td class="num"><input type="number" min="1" step="1" value="${cant}" data-cant="${id}" class="cant"></td>
        <td class="num">${soles(Number(p.precio) * cant)}</td>
        <td><button class="mini sec" data-quitar="${id}" title="Quitar">✕</button></td></tr>`;
    }).join('');
  }
  actualizarTotales();
}

function actualizarTotales() {
  const total = totalCarrito();
  $('total').textContent = soles(total);
  const efectivo = $('metodoPago').value === 'EFECTIVO';
  $('lblRecibido').style.display = efectivo ? '' : 'none';
  $('filaVuelto').style.display = efectivo ? '' : 'none';
  const recibido = $('montoRecibido').value === '' ? total : Number($('montoRecibido').value);
  $('vuelto').textContent = soles(Math.max(recibido - total, 0));

  const tipo = $('tipoComprobante').value;
  const factura = tipo === 'FACTURA';
  const pideDocumento = factura || (tipo === 'BOLETA' && total >= BOLETA_CON_DOCUMENTO);
  $('lblDocumento').textContent = factura ? 'RUC del cliente (11 dígitos)'
    : pideDocumento ? 'DNI / RUC del cliente (obligatorio desde S/ 700)' : 'DNI / RUC del cliente (opcional)';
  $('lblNombre').textContent = factura ? 'Razón social' : 'Nombre del cliente (opcional)';
  $('clienteDocumento').required = pideDocumento;
  $('clienteDocumento').pattern = factura ? '\\d{11}' : '\\d{8}|\\d{11}';
  $('clienteNombre').required = factura;

  $('btnCobrar').disabled = carrito.size === 0 || (efectivo && recibido < total);
}

// ---------------------------------------------------------------- comprobante

function htmlTicket(v) {
  const filas = v.detalles.map(d => `<tr>
      <td>${d.cantidad} × ${esc(d.producto)}<div class="muted">${esc(d.codigo)} · ${soles(d.precioUnitario)}</div></td>
      <td class="num">${soles(d.importe)}</td></tr>`).join('');
  return `
    <div class="ticket-cab">
      <div class="logo-ticket">Smart<span>Stock</span> IoT</div>
      <div>${esc(NOMBRE_COMPROBANTE[v.tipoComprobante])}</div>
      <div class="numero">${esc(v.numero)}</div>
      <div class="muted">${fecha(v.fecha)} · ${esc(v.ubicacion)}</div>
    </div>
    ${v.estado === 'ANULADA' ? `<div class="anulada">ANULADA · ${esc(v.motivoAnulacion)}</div>` : ''}
    ${v.clienteDocumento || v.clienteNombre ? `<div class="cliente">Cliente: ${esc(v.clienteNombre ?? '')} ${v.clienteDocumento ? '· ' + esc(v.clienteDocumento) : ''}</div>` : ''}
    <table>${filas}</table>
    <table class="resumen">
      <tr><td>Op. gravada</td><td class="num">${soles(v.subtotal)}</td></tr>
      <tr><td>IGV</td><td class="num">${soles(v.igv)}</td></tr>
      <tr class="fuerte"><td>Total</td><td class="num">${soles(v.total)}</td></tr>
      <tr><td>Pago</td><td class="num">${esc(v.metodoPago)}</td></tr>
      ${v.montoRecibido != null ? `<tr><td>Recibido</td><td class="num">${soles(v.montoRecibido)}</td></tr>
      <tr><td>Vuelto</td><td class="num">${soles(v.vuelto)}</td></tr>` : ''}
    </table>
    <div class="pie-ticket">Atendido por ${esc(v.vendedor)}<br>Gracias por su compra</div>`;
}

function mostrarTicket(v) {
  $('ticket').innerHTML = htmlTicket(v);
  $('modalTicket').hidden = false;
  document.body.classList.add('con-ticket');
}

function cerrarTicket() {
  $('modalTicket').hidden = true;
  document.body.classList.remove('con-ticket');
}

// ---------------------------------------------------------------- consultas

async function cargarResumen() {
  try {
    const r = await API.get('/api/ventas/resumen');
    $('kpis').innerHTML = `
      <div class="kpi"><div class="l">Ventas de hoy</div><div class="v">${r.ventasHoy}</div></div>
      <div class="kpi ok"><div class="l">Total de hoy</div><div class="v">${soles(r.totalHoy)}</div></div>
      <div class="kpi"><div class="l">Ticket promedio</div><div class="v">${soles(r.ticketPromedioHoy)}</div></div>
      <div class="kpi"><div class="l">Ventas del mes</div><div class="v">${r.ventasMes}</div></div>
      <div class="kpi ok"><div class="l">Total del mes</div><div class="v">${soles(r.totalMes)}</div></div>`;
    const tb = $('tbTop');
    if (r.masVendidosMes.length === 0) return estadoTabla(tb, 4, 'Aún no hay ventas este mes');
    tb.innerHTML = r.masVendidosMes.map(p => `<tr><td>${esc(p.codigo)}</td><td>${esc(p.nombre)}</td>
      <td class="num">${p.unidades}</td><td class="num">${soles(p.importe)}</td></tr>`).join('');
  } catch (e) { $('kpis').innerHTML = `<div class="estado error">${esc(e.message)}</div>`; }
}

let ventasCargadas = [];

async function cargarVentas() {
  const tb = $('tbVentas');
  estadoTabla(tb, 8, 'Cargando...');
  const params = new URLSearchParams({ tamano: 100 });
  if ($('fDesde').value) params.set('desde', $('fDesde').value);
  if ($('fHasta').value) params.set('hasta', $('fHasta').value);
  if ($('fEstado').value) params.set('estado', $('fEstado').value);
  try {
    const r = await API.get('/api/ventas?' + params);
    ventasCargadas = r.contenido;
    if (ventasCargadas.length === 0) return estadoTabla(tb, 8, 'Sin ventas en el periodo');
    tb.innerHTML = ventasCargadas.map(v => `<tr>
      <td>${esc(v.numero)}</td><td>${fecha(v.fecha)}</td><td>${esc(v.clienteNombre ?? v.clienteDocumento ?? '-')}</td>
      <td>${esc(v.metodoPago)}</td><td class="num">${soles(v.total)}</td>
      <td><span class="tag ${TAG_VENTA[v.estado]}">${esc(v.estado)}</span></td><td>${esc(v.vendedor)}</td>
      <td><button class="mini sec" data-ver="${v.id}">Ver</button>
        ${esAdmin() && v.estado === 'EMITIDA' ? `<button class="mini peligro" data-anular="${v.id}">Anular</button>` : ''}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 8, e.message, true); }
}

async function refrescar() {
  await cargarDisponibles().catch(e => aviso('aviso', 'error', e.message));
  pintarCarrito();
  cargarResumen();
  cargarVentas();
}

// ---------------------------------------------------------------- eventos

$('formItem').addEventListener('submit', (e) => {
  e.preventDefault();
  const p = productoDe($('buscar').value);
  if (!p) return aviso('aviso', 'error', 'Elija un producto de la lista');
  const cant = Math.max(parseInt($('cantidad').value, 10) || 1, 1);
  carrito.set(p.id, (carrito.get(p.id) ?? 0) + cant);
  $('buscar').value = '';
  $('cantidad').value = 1;
  $('buscar').focus();
  pintarCarrito();
});

$('tbCarrito').addEventListener('change', (e) => {
  const id = Number(e.target.dataset.cant);
  if (!id) return;
  const cant = parseInt(e.target.value, 10);
  if (cant > 0) carrito.set(id, cant); else carrito.delete(id);
  pintarCarrito();
});

$('tbCarrito').addEventListener('click', (e) => {
  const id = Number(e.target.dataset.quitar);
  if (!id) return;
  carrito.delete(id);
  pintarCarrito();
});

$('selUbicacion').addEventListener('change', async () => {
  await cargarDisponibles().catch(err => aviso('aviso', 'error', err.message));
  pintarCarrito();
});
for (const id of ['tipoComprobante', 'metodoPago', 'montoRecibido']) $(id).addEventListener('input', actualizarTotales);

$('btnVaciar').addEventListener('click', () => { carrito.clear(); pintarCarrito(); });

$('formCobro').addEventListener('submit', async (e) => {
  e.preventDefault();
  if (carrito.size === 0) return;
  const datos = formJSON(e.target);
  datos.ubicacionId = Number($('selUbicacion').value);
  datos.items = [...carrito].map(([productoId, cantidad]) => ({ productoId, cantidad }));
  if (datos.metodoPago !== 'EFECTIVO') delete datos.montoRecibido;
  $('btnCobrar').disabled = true;
  try {
    const venta = await API.post('/api/ventas', datos);
    carrito.clear();
    e.target.reset();
    aviso('aviso', 'ok', `Venta ${venta.numero} registrada por ${soles(venta.total)}`);
    mostrarTicket(venta);
    refrescar();
  } catch (err) {
    aviso('aviso', 'error', err.message);
    actualizarTotales();
  }
});

$('tbVentas').addEventListener('click', async (e) => {
  const ver = Number(e.target.dataset.ver);
  const anular = Number(e.target.dataset.anular);
  if (ver) {
    const v = ventasCargadas.find(x => x.id === ver);
    if (v) mostrarTicket(v);
  } else if (anular) {
    const v = ventasCargadas.find(x => x.id === anular);
    const motivo = prompt(`Motivo de la anulación de ${v.numero} (el stock se devuelve a ${v.ubicacion}):`);
    if (!motivo || !motivo.trim()) return;
    try {
      await API.post(`/api/ventas/${anular}/anular`, { motivo: motivo.trim() });
      aviso('aviso', 'ok', `Venta ${v.numero} anulada`);
      refrescar();
    } catch (err) { aviso('aviso', 'error', err.message); }
  }
});

$('btnFiltrar').addEventListener('click', cargarVentas);
$('btnCerrarTicket').addEventListener('click', cerrarTicket);
$('btnImprimir').addEventListener('click', () => {
  if (window.escritorio && window.escritorio.imprimir) window.escritorio.imprimir();
  else window.print();
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') cerrarTicket(); });

(async () => {
  try { await iniciarPagina('ventas.html'); } catch { return; }
  try {
    const [prods, ubs] = await Promise.all([API.todosLosProductos(true), API.get('/api/ubicaciones')]);
    productos = prods.filter(p => p.activo && Number(p.precio) > 0);
    $('listaProductos').innerHTML = productos
      .map(p => `<option value="${esc(p.codigo + ' - ' + p.nombre)}">${soles(p.precio)}</option>`).join('');
    $('selUbicacion').innerHTML = ubs.filter(u => u.activo)
      .map(u => `<option value="${u.id}">${esc(u.nombre)}</option>`).join('');
  } catch (e) { aviso('aviso', 'error', e.message); }
  refrescar();
})();

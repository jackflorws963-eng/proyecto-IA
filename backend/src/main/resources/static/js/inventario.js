'use strict';

const TAG_TIPO = { ENTRADA: 'ok', SALIDA: 'warn', TRANSFERENCIA: 'info', AJUSTE_POSITIVO: 'gris', AJUSTE_NEGATIVO: 'bad' };

function opciones(lista, valor, texto, vacio) {
  return (vacio ? `<option value="">${vacio}</option>` : '') +
    lista.map(x => `<option value="${x[valor]}">${esc(texto(x))}</option>`).join('');
}

function ajustarCampos() {
  const tipo = document.getElementById('tipo').value;
  const usaOrigen = ['SALIDA', 'AJUSTE_NEGATIVO', 'TRANSFERENCIA'].includes(tipo);
  const usaDestino = ['ENTRADA', 'AJUSTE_POSITIVO', 'TRANSFERENCIA'].includes(tipo);
  document.getElementById('lblOrigen').style.display = usaOrigen ? '' : 'none';
  document.getElementById('lblDestino').style.display = usaDestino ? '' : 'none';
  document.getElementById('selOrigen').required = usaOrigen;
  document.getElementById('selDestino').required = usaDestino;
}

async function cargarListas() {
  const [prods, ubs] = await Promise.all([
    API.todosLosProductos(false),
    API.get('/api/ubicaciones'),
  ]);
  const texto = p => `${p.codigo} - ${p.nombre}`;
  const activos = prods.filter(p => p.activo);
  document.getElementById('selProducto').innerHTML = opciones(activos, 'id', texto);
  document.getElementById('fProducto').innerHTML = opciones(prods, 'id', texto, 'Todos');
  document.getElementById('hProducto').innerHTML = opciones(prods, 'id', texto, 'Todos');
  const ubsActivas = ubs.filter(u => u.activo);
  document.getElementById('selOrigen').innerHTML = opciones(ubsActivas, 'id', u => u.nombre);
  document.getElementById('selDestino').innerHTML = opciones(ubsActivas, 'id', u => u.nombre);
  document.getElementById('fUbicacion').innerHTML = opciones(ubs, 'id', u => u.nombre, 'Todas');
}

async function cargarInventario() {
  const tb = document.getElementById('tbInventario');
  const params = new URLSearchParams();
  const p = document.getElementById('fProducto').value;
  const u = document.getElementById('fUbicacion').value;
  if (p) params.set('productoId', p);
  if (u) params.set('ubicacionId', u);
  try {
    const lista = await API.get('/api/inventario?' + params);
    if (lista.length === 0) return estadoTabla(tb, 6, 'Sin existencias registradas');
    tb.innerHTML = lista.map(i => `<tr>
      <td>${esc(i.productoCodigo)}</td><td>${esc(i.productoNombre)}</td><td>${esc(i.ubicacion)}</td>
      <td class="num">${i.cantidad}</td><td class="num">${i.stockMinimo}</td><td>${fecha(i.actualizado)}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 6, e.message, true); }
}

async function cargarMovimientos() {
  const tb = document.getElementById('tbMovs');
  estadoTabla(tb, 8, 'Cargando...');
  const params = new URLSearchParams({ tamano: 100 });
  const p = document.getElementById('hProducto').value;
  if (p) params.set('productoId', p);
  const us = document.getElementById('hUsuario').value.trim();
  if (us) params.set('usuario', us);
  const d = document.getElementById('hDesde').value;
  const h = document.getElementById('hHasta').value;
  if (d) params.set('desde', d);
  if (h) params.set('hasta', h);
  try {
    const r = await API.get('/api/movimientos?' + params);
    if (r.contenido.length === 0) return estadoTabla(tb, 8, 'Sin movimientos en el periodo');
    tb.innerHTML = r.contenido.map(m => `<tr>
      <td>${fecha(m.fecha)}</td><td><span class="tag ${TAG_TIPO[m.tipo]}">${esc(m.tipo)}</span></td>
      <td>${esc(m.producto)}</td><td>${esc(m.ubicacionOrigen ?? '-')}</td><td>${esc(m.ubicacionDestino ?? '-')}</td>
      <td class="num">${m.cantidad}</td><td style="white-space:normal">${esc(m.motivo)}</td><td>${esc(m.usuario)}</td></tr>`).join('');
  } catch (e) { estadoTabla(tb, 8, e.message, true); }
}

document.getElementById('tipo').addEventListener('change', ajustarCampos);

document.getElementById('formMov').addEventListener('submit', async (e) => {
  e.preventDefault();
  const datos = formJSON(e.target);
  const tipo = datos.tipo;
  if (!['SALIDA', 'AJUSTE_NEGATIVO', 'TRANSFERENCIA'].includes(tipo)) delete datos.ubicacionOrigenId;
  if (!['ENTRADA', 'AJUSTE_POSITIVO', 'TRANSFERENCIA'].includes(tipo)) delete datos.ubicacionDestinoId;
  for (const k of ['productoId', 'ubicacionOrigenId', 'ubicacionDestinoId']) if (datos[k]) datos[k] = Number(datos[k]);
  try {
    await API.post('/api/movimientos', datos);
    aviso('aviso', 'ok', 'Movimiento registrado');
    e.target.elements.cantidad.value = '';
    e.target.elements.motivo.value = '';
    cargarInventario();
    cargarMovimientos();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('fProducto').addEventListener('change', cargarInventario);
document.getElementById('fUbicacion').addEventListener('change', cargarInventario);
document.getElementById('btnFiltrar').addEventListener('click', cargarMovimientos);

(async () => {
  try { await iniciarPagina('inventario.html'); } catch { return; }
  if (!esAdmin()) document.querySelectorAll('#tipo option.solo-admin').forEach(o => o.remove());
  ajustarCampos();
  try { await cargarListas(); } catch (e) { aviso('aviso', 'error', e.message); }
  cargarInventario();
  cargarMovimientos();
})();

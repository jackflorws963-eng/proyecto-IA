'use strict';

let pagina = 0;
let totalPaginas = 1;
let productosCache = [];

async function cargarCategorias() {
  const cats = await API.get('/api/categorias');
  const activas = cats.filter(c => c.activo);
  document.getElementById('selCategoria').innerHTML = activas.map(c => `<option value="${c.id}">${esc(c.nombre)}</option>`).join('');
  document.getElementById('filtroCategoria').innerHTML = '<option value="">Todas</option>' +
    cats.map(c => `<option value="${c.id}">${esc(c.nombre)}</option>`).join('');
  document.getElementById('tbCategorias').innerHTML = cats.map(c => `<tr><td>${esc(c.nombre)}</td>
    <td>${esc(c.descripcion)}</td><td><span class="tag ${c.activo ? 'ok' : 'gris'}">${c.activo ? 'Activa' : 'Inactiva'}</span></td></tr>`).join('');
}

async function cargarUbicaciones() {
  const ubs = await API.get('/api/ubicaciones');
  document.getElementById('tbUbicaciones').innerHTML = ubs.map(u => `<tr><td>${esc(u.nombre)}</td>
    <td>${num(u.tempMin)} a ${num(u.tempMax)} °C</td><td>${num(u.humMin)} a ${num(u.humMax)} %</td></tr>`).join('');
}

async function cargarProductos() {
  const tb = document.getElementById('tbProductos');
  estadoTabla(tb, 10, 'Cargando...');
  const params = new URLSearchParams({
    q: document.getElementById('q').value,
    soloActivos: document.getElementById('filtroActivos').value,
    pagina, tamano: 15,
  });
  const cat = document.getElementById('filtroCategoria').value;
  if (cat) params.set('categoriaId', cat);
  try {
    const r = await API.get('/api/productos?' + params);
    productosCache = r.contenido;
    totalPaginas = Math.max(r.totalPaginas, 1);
    document.getElementById('infoPagina').textContent = `Página ${r.pagina + 1} de ${totalPaginas} · ${r.totalElementos} productos`;
    if (r.contenido.length === 0) return estadoTabla(tb, 10, 'No se encontraron productos');
    tb.innerHTML = r.contenido.map(p => `<tr>
      <td>${esc(p.codigo)}</td><td>${esc(p.nombre)}</td><td>${esc(p.categoria)}</td><td>${esc(p.unidad)}</td>
      <td class="num">${num(p.precio)}</td><td class="num">${num(p.pesoUnitarioG, 3)}</td>
      <td class="num">${p.stockTotal < p.stockMinimo ? `<span class="tag bad">${p.stockTotal}</span>` : p.stockTotal}</td>
      <td class="num">${p.stockMinimo}</td>
      <td><span class="tag ${p.activo ? 'ok' : 'gris'}">${p.activo ? 'Activo' : 'Inactivo'}</span></td>
      <td class="solo-admin" ${esAdmin() ? '' : 'style="display:none"'}>
        <button class="sec mini" data-editar="${p.id}">Editar</button>
        <button class="${p.activo ? 'peligro' : 'sec'} mini" data-estado="${p.id}" data-activo="${!p.activo}">${p.activo ? 'Desactivar' : 'Activar'}</button>
      </td></tr>`).join('');
  } catch (e) {
    estadoTabla(tb, 10, e.message, true);
  }
}

function limpiarForm() {
  const f = document.getElementById('formProducto');
  f.reset();
  f.elements.id.value = '';
  document.getElementById('tituloForm').textContent = 'Nuevo producto';
}

document.getElementById('formProducto').addEventListener('submit', async (e) => {
  e.preventDefault();
  const datos = formJSON(e.target);
  const id = datos.id;
  delete datos.id;
  datos.categoriaId = Number(datos.categoriaId);
  try {
    if (id) await API.put('/api/productos/' + id, datos);
    else await API.post('/api/productos', datos);
    aviso('aviso', 'ok', 'Producto guardado');
    limpiarForm();
    cargarProductos();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('btnCancelar').addEventListener('click', limpiarForm);

document.getElementById('tbProductos').addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  if (btn.dataset.editar) {
    const p = productosCache.find(x => x.id === Number(btn.dataset.editar));
    const f = document.getElementById('formProducto');
    f.elements.id.value = p.id;
    f.elements.codigo.value = p.codigo;
    f.elements.nombre.value = p.nombre;
    f.elements.categoriaId.value = p.categoriaId;
    f.elements.unidad.value = p.unidad;
    f.elements.precio.value = p.precio;
    f.elements.pesoUnitarioG.value = p.pesoUnitarioG ?? '';
    f.elements.stockMinimo.value = p.stockMinimo;
    f.elements.descripcion.value = p.descripcion ?? '';
    document.getElementById('tituloForm').textContent = 'Editar producto ' + p.codigo;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } else if (btn.dataset.estado) {
    try {
      await API.patch(`/api/productos/${btn.dataset.estado}/estado?activo=${btn.dataset.activo}`);
      aviso('aviso', 'ok', 'Estado actualizado');
      cargarProductos();
    } catch (err) { aviso('aviso', 'error', err.message); }
  }
});

document.getElementById('formCategoria').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await API.post('/api/categorias', formJSON(e.target));
    e.target.reset();
    aviso('aviso', 'ok', 'Categoría creada');
    cargarCategorias();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

document.getElementById('formUbicacion').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await API.post('/api/ubicaciones', formJSON(e.target));
    e.target.reset();
    aviso('aviso', 'ok', 'Ubicación creada');
    cargarUbicaciones();
  } catch (err) { aviso('aviso', 'error', err.message); }
});

let temporizador;
document.getElementById('q').addEventListener('input', () => {
  clearTimeout(temporizador);
  temporizador = setTimeout(() => { pagina = 0; cargarProductos(); }, 300);
});
document.getElementById('filtroCategoria').addEventListener('change', () => { pagina = 0; cargarProductos(); });
document.getElementById('filtroActivos').addEventListener('change', () => { pagina = 0; cargarProductos(); });
document.getElementById('btnAnt').addEventListener('click', () => { if (pagina > 0) { pagina--; cargarProductos(); } });
document.getElementById('btnSig').addEventListener('click', () => { if (pagina < totalPaginas - 1) { pagina++; cargarProductos(); } });

(async () => {
  try { await iniciarPagina('productos.html'); } catch { return; }
  try {
    await Promise.all([cargarCategorias(), cargarUbicaciones()]);
  } catch (e) { aviso('aviso', 'error', e.message); }
  cargarProductos();
})();

'use strict';

document.getElementById('formLogin').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = document.getElementById('btn');
  const aviso = document.getElementById('aviso');
  btn.disabled = true; btn.textContent = 'Ingresando...';
  try {
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...csrfHeaders('POST') },
      body: new URLSearchParams(new FormData(e.target)),
    });
    if (resp.ok) { location.href = '/index.html'; return; }
    const datos = await resp.json().catch(() => ({}));
    aviso.className = 'aviso error';
    aviso.textContent = datos.mensaje || 'No se pudo iniciar sesión';
  } catch {
    aviso.className = 'aviso error';
    aviso.textContent = 'No se pudo conectar con el servidor';
  } finally {
    btn.disabled = false; btn.textContent = 'Ingresar';
  }
});

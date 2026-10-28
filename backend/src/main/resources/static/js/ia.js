'use strict';

// SmartStock IoT - asistente de IA local (sin internet).  Autor: Brayan (BR)
// Chat reutilizable (pagina del asistente y burbuja flotante en todas las pantallas), voz y efectos
// sobre la interfaz: abrir pantallas, descargar reportes e imprimir. La conversacion vive en el
// servidor, asi que continua al cambiar de pantalla.

(() => {
  if (window.IA) return;

  let vozActiva = true;
  try { vozActiva = localStorage.getItem('asistenteVoz') !== 'no'; } catch { /* sin almacenamiento */ }

  // ---------------------------------------------------------------- voz
  const Voz = {
    escritorio() { return !!(window.escritorio && window.escritorio.vozDisponible && window.escritorio.vozDisponible()); },
    activa() { return vozActiva; },
    alternar() {
      vozActiva = !vozActiva;
      try { localStorage.setItem('asistenteVoz', vozActiva ? 'si' : 'no'); } catch { /* sin almacenamiento */ }
      if (!vozActiva) this.callar();
      return vozActiva;
    },
    hablar(texto) {
      if (!vozActiva || !texto) return;
      if (this.escritorio()) { window.escritorio.hablar(texto); return; }
      if ('speechSynthesis' in window) {
        speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance(texto);
        u.lang = 'es-ES';
        speechSynthesis.speak(u);
      }
    },
    callar() {
      if (this.escritorio()) window.escritorio.callar();
      else if ('speechSynthesis' in window) speechSynthesis.cancel();
    },
    puedeEscuchar() { return this.escritorio() || !!(window.SpeechRecognition || window.webkitSpeechRecognition); },
    escuchar() {
      this.callar();
      if (this.escritorio()) { window.escritorio.escuchar(); return; }
      const R = window.SpeechRecognition || window.webkitSpeechRecognition;
      const r = new R();
      let entendido = '';
      r.lang = 'es-ES';
      r.onstart = () => window.vozEvento('escuchando', '');
      r.onresult = (e) => { entendido = e.results[0][0].transcript; };
      r.onerror = (e) => { if (e.error !== 'no-speech') window.vozEvento('error', 'Error de voz: ' + e.error); };
      r.onend = () => window.vozEvento('texto', entendido);
      r.start();
    },
  };

  // Java (PuenteEscritorio) y la Web Speech API avisan aqui; lo recibe el chat que pidio escuchar
  let escuchaActual = null;
  window.vozEvento = (tipo, dato) => { if (escuchaActual) escuchaActual(tipo, dato); };

  // ---------------------------------------------------------------- efectos sobre la interfaz
  function aplicarEfectos(r) {
    if (r.descargar) {
      if (window.escritorio && window.escritorio.descargar) window.escritorio.descargar(r.descargar);
      else location.href = r.descargar;
    }
    if (r.imprimir) {
      if (window.escritorio && window.escritorio.imprimir) window.escritorio.imprimir();
      else window.print();
    }
    if (r.navegar && r.navegar !== location.pathname) {
      try { sessionStorage.setItem('iaAbierta', '1'); } catch { /* sin almacenamiento */ }
      setTimeout(() => { location.href = r.navegar; }, 900);
    }
  }

  const MOTOR = { MODELO: 'IA local', PROPIO: 'Motor propio' };

  // ---------------------------------------------------------------- chat
  function crearChat(contenedor, opciones = {}) {
    contenedor.innerHTML = `
      <div class="chat" data-mensajes aria-live="polite"></div>
      ${opciones.sugerencias ? `<div class="chips" data-chips>${opciones.sugerencias.map(s =>
        `<button class="sec mini" type="button">${esc(s)}</button>`).join('')}</div>` : ''}
      <form class="fila chat-form" autocomplete="off">
        <input name="texto" maxlength="1000" placeholder="Escribe o pulsa Hablar" style="flex:1">
        <button class="sec mic" type="button" data-mic title="Hablar">Hablar</button>
        <button type="submit">Enviar</button>
      </form>
      <div class="muted chat-estado" data-estado></div>`;
    const lista = contenedor.querySelector('[data-mensajes]');
    const form = contenedor.querySelector('form');
    const input = form.elements.texto;
    const mic = contenedor.querySelector('[data-mic]');
    const estado = contenedor.querySelector('[data-estado]');
    let ultimoTexto = '';
    let ocupado = false;

    function burbuja(quien, texto) {
      const div = document.createElement('div');
      div.className = 'msg ' + quien;
      div.textContent = texto;
      lista.appendChild(div);
      lista.scrollTop = lista.scrollHeight;
      return div;
    }

    function pintarPropuestas(div, acciones) {
      acciones.forEach(a => {
        const caja = document.createElement('div');
        caja.className = 'propuesta';
        caja.innerHTML = `<div>${esc(a.descripcion)}</div>
          <div class="confirmar"><button class="mini" data-si>Confirmar</button> <button class="sec mini" data-no>Cancelar</button></div>`;
        caja.querySelector('[data-si]').addEventListener('click', () => resolver(caja, `/api/ia/confirmar/${a.id}`));
        caja.querySelector('[data-no]').addEventListener('click', () => resolver(caja, `/api/ia/cancelar/${a.id}`));
        div.appendChild(caja);
      });
      lista.scrollTop = lista.scrollHeight;
    }

    function pintarOpciones(div, opcionesIa, texto) {
      const caja = document.createElement('div');
      caja.className = 'chips';
      caja.style.marginTop = '8px';
      opcionesIa.forEach(o => {
        const b = document.createElement('button');
        b.className = 'sec mini';
        b.type = 'button';
        b.textContent = o.etiqueta;
        b.addEventListener('click', async () => {
          caja.querySelectorAll('button').forEach(x => { x.disabled = true; });
          burbuja('usuario', o.etiqueta);
          procesar(await llamar('/api/ia/aprender', { texto, intencion: o.intencion }));
        });
        caja.appendChild(b);
      });
      div.appendChild(caja);
    }

    async function resolver(caja, url) {
      caja.querySelectorAll('button').forEach(x => { x.disabled = true; });
      procesar(await llamar(url, {}));
      if (opciones.alCambiar) opciones.alCambiar();
    }

    async function llamar(url, cuerpo) {
      estado.textContent = 'Pensando…';
      try {
        return await API.post(url, cuerpo);
      } catch (e) {
        return { texto: e.message, error: true, porConfirmar: [], opciones: [] };
      } finally {
        estado.textContent = '';
      }
    }

    function procesar(r) {
      if (!r) return;
      const div = burbuja(r.error ? 'sistema' : 'ia', r.texto);
      if (r.motor) {
        const m = document.createElement('div');
        m.className = 'motor';
        m.textContent = MOTOR[r.motor] || r.motor;
        div.prepend(m);
      }
      if (r.porConfirmar && r.porConfirmar.length) pintarPropuestas(div, r.porConfirmar);
      if (r.opciones && r.opciones.length) pintarOpciones(div, r.opciones, ultimoTexto);
      if (!r.error) Voz.hablar(r.texto);
      aplicarEfectos(r);
    }

    async function enviar(texto) {
      if (ocupado || !texto) return;
      ocupado = true;
      ultimoTexto = texto;
      burbuja('usuario', texto);
      try {
        procesar(await llamar('/api/ia/mensaje', { texto }));
      } finally {
        ocupado = false;
      }
      if (opciones.alCambiar) opciones.alCambiar();
    }

    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const t = input.value.trim();
      input.value = '';
      enviar(t);
    });
    const chips = contenedor.querySelector('[data-chips]');
    if (chips) chips.addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) enviar(b.textContent); });

    mic.addEventListener('click', () => {
      if (!Voz.puedeEscuchar()) { burbuja('sistema', 'El reconocimiento de voz no está disponible aquí. Usa la app de escritorio o Chrome/Edge.'); return; }
      escuchaActual = (tipo, dato) => {
        if (tipo === 'escuchando') { mic.classList.add('escuchando'); mic.textContent = '● Escuchando'; estado.textContent = 'Te escucho…'; return; }
        mic.classList.remove('escuchando');
        mic.textContent = 'Hablar';
        estado.textContent = '';
        if (tipo === 'error') burbuja('sistema', dato);
        else if (dato && dato.trim()) enviar(dato.trim());
        else estado.textContent = 'No te entendí. Inténtalo de nuevo.';
      };
      estado.textContent = 'Preparando el micrófono…';
      Voz.escuchar();
    });

    // Continua la conversacion (tambien la que empezo en otra pantalla)
    (async () => {
      try {
        const [historial, pendientes] = await Promise.all([API.get('/api/ia/historial'), API.get('/api/ia/pendientes')]);
        historial.forEach(m => burbuja(m.rol, m.texto));
        if (pendientes.length) pintarPropuestas(burbuja('ia', 'Tienes acciones esperando tu confirmación:'), pendientes);
        if (!historial.length && opciones.bienvenida) burbuja('ia', opciones.bienvenida);
      } catch { /* sin historial */ }
    })();

    return { enviar, decir: (texto) => { burbuja('ia', texto); Voz.hablar(texto); }, foco: () => input.focus() };
  }

  // ---------------------------------------------------------------- burbuja flotante
  function crearBurbuja() {
    if (document.body.dataset.sinBurbuja !== undefined || document.getElementById('iaBurbuja')) return;
    const boton = document.createElement('button');
    boton.id = 'iaBurbuja';
    boton.className = 'ia-burbuja';
    boton.type = 'button';
    boton.title = 'Asistente IA (Ctrl+I)';
    boton.innerHTML = '<img src="/img/logo.png" alt=""><span>IA</span>';
    const panel = document.createElement('section');
    panel.className = 'ia-panel';
    panel.hidden = true;
    panel.innerHTML = `<div class="ia-cabecera"><strong>Asistente IA</strong><span class="muted" data-motor></span>
      <button class="sec mini" type="button" data-cerrar title="Cerrar">✕</button></div><div data-chat></div>`;
    document.body.append(panel, boton);
    let chat = null;

    async function abrir() {
      panel.hidden = false;
      try { sessionStorage.setItem('iaAbierta', '1'); } catch { /* sin almacenamiento */ }
      if (!chat) {
        chat = crearChat(panel.querySelector('[data-chat]'), {
          bienvenida: 'Hola, soy tu asistente. Puedo consultar y operar todo SmartStock. ¿Qué necesitas?',
        });
        try {
          const e = await API.get('/api/ia/estado');
          panel.querySelector('[data-motor]').textContent = e.modeloDisponible ? `${e.modelo} · sin internet` : 'motor propio · sin internet';
        } catch { /* informativo */ }
      }
      chat.foco();
    }
    function cerrar() {
      panel.hidden = true;
      try { sessionStorage.removeItem('iaAbierta'); } catch { /* sin almacenamiento */ }
    }
    boton.addEventListener('click', () => (panel.hidden ? abrir() : cerrar()));
    panel.querySelector('[data-cerrar]').addEventListener('click', cerrar);
    document.addEventListener('keydown', (e) => {
      if (e.ctrlKey && (e.key === 'i' || e.key === 'I')) { e.preventDefault(); panel.hidden ? abrir() : cerrar(); }
    });
    let abierta = false;
    try { abierta = sessionStorage.getItem('iaAbierta') === '1'; } catch { /* sin almacenamiento */ }
    if (abierta) abrir();
  }

  window.IA = { crearChat, crearBurbuja, Voz, aplicarEfectos };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', crearBurbuja);
  else crearBurbuja();
})();

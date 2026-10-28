package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import pe.edu.unaj.smartstock.dto.Dtos.MovimientoRequest;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.TipoMovimiento;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.*;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MovimientoServiceTest {

    @Mock MovimientoRepository movimientos;
    @Mock InventarioRepository inventario;
    @Mock ProductoRepository productos;
    @Mock UbicacionRepository ubicaciones;
    @Mock UsuarioRepository usuarios;
    @Mock AuditoriaService auditoria;
    @Mock ReglasStockService reglasStock;
    @InjectMocks MovimientoService service;

    Producto producto;
    Ubicacion almacen;
    Usuario operador;

    @BeforeEach
    void datos() {
        Categoria c = new Categoria();
        c.setId(1L);
        c.setNombre("Ferreteria");
        producto = new Producto();
        producto.setId(1L);
        producto.setCodigo("P001");
        producto.setNombre("Pernos");
        producto.setCategoria(c);
        producto.setStockMinimo(5);
        producto.setPrecio(BigDecimal.ONE);
        almacen = new Ubicacion();
        almacen.setId(1L);
        almacen.setNombre("Almacen A");
        operador = new Usuario();
        operador.setUsername("operador");
    }

    private Inventario inventarioCon(int cantidad) {
        Inventario i = new Inventario(producto, almacen);
        i.setCantidad(cantidad);
        return i;
    }

    @Test
    void salidaMayorAlStockSeRechaza() {
        when(productos.findById(1L)).thenReturn(Optional.of(producto));
        when(usuarios.findByUsername("operador")).thenReturn(Optional.of(operador));
        when(ubicaciones.findById(1L)).thenReturn(Optional.of(almacen));
        Inventario inv = inventarioCon(3);
        when(inventario.bloquear(1L, 1L)).thenReturn(Optional.of(inv));

        var req = new MovimientoRequest(TipoMovimiento.SALIDA, 1L, 1L, null, 5, "Venta");
        NegocioException ex = assertThrows(NegocioException.class, () -> service.registrar(req, "operador", false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(3, inv.getCantidad(), "el stock no debe cambiar");
        verify(movimientos, never()).save(any());
    }

    @Test
    void entradaSumaStock() {
        when(productos.findById(1L)).thenReturn(Optional.of(producto));
        when(usuarios.findByUsername("operador")).thenReturn(Optional.of(operador));
        when(ubicaciones.findById(1L)).thenReturn(Optional.of(almacen));
        Inventario inv = inventarioCon(10);
        when(inventario.bloquear(1L, 1L)).thenReturn(Optional.of(inv));
        when(movimientos.save(any(Movimiento.class))).thenAnswer(a -> a.getArgument(0));

        var req = new MovimientoRequest(TipoMovimiento.ENTRADA, 1L, null, 1L, 7, "Compra");
        var resp = service.registrar(req, "operador", false);

        assertEquals(17, inv.getCantidad());
        assertEquals(TipoMovimiento.ENTRADA, resp.tipo());
        verify(auditoria).registrar(eq("MOVIMIENTO_ENTRADA"), eq("movimiento"), any(), anyString());
        verify(reglasStock).evaluarStockMinimo(producto);
    }

    @Test
    void operadorNoPuedeHacerAjustes() {
        var req = new MovimientoRequest(TipoMovimiento.AJUSTE_NEGATIVO, 1L, 1L, null, 1, "Conteo");
        NegocioException ex = assertThrows(NegocioException.class, () -> service.registrar(req, "operador", false));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void cantidadCeroONegativaSeRechaza() {
        var req = new MovimientoRequest(TipoMovimiento.ENTRADA, 1L, null, 1L, -4, "Error");
        assertThrows(NegocioException.class, () -> service.registrar(req, "operador", false));
        var req0 = new MovimientoRequest(TipoMovimiento.ENTRADA, 1L, null, 1L, 0, "Error");
        assertThrows(NegocioException.class, () -> service.registrar(req0, "operador", false));
    }

    @Test
    void entradaConOrigenEsInvalida() {
        when(productos.findById(1L)).thenReturn(Optional.of(producto));
        when(usuarios.findByUsername("operador")).thenReturn(Optional.of(operador));
        var req = new MovimientoRequest(TipoMovimiento.ENTRADA, 1L, 1L, 1L, 2, "Compra");
        assertThrows(NegocioException.class, () -> service.registrar(req, "operador", false));
    }
}

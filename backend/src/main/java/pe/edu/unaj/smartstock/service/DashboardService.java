package pe.edu.unaj.smartstock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.ResumenDashboard;
import pe.edu.unaj.smartstock.entity.Dispositivo;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.EstadoDiscrepancia;
import pe.edu.unaj.smartstock.repository.*;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DashboardService {

    private final ProductoRepository productos;
    private final InventarioRepository inventario;
    private final AlertaRepository alertas;
    private final DiscrepanciaRepository discrepancias;
    private final DispositivoRepository dispositivos;
    private final LecturaRepository lecturas;
    private final DispositivoService dispositivoService;

    public DashboardService(ProductoRepository productos, InventarioRepository inventario, AlertaRepository alertas,
                            DiscrepanciaRepository discrepancias, DispositivoRepository dispositivos,
                            LecturaRepository lecturas, DispositivoService dispositivoService) {
        this.productos = productos;
        this.inventario = inventario;
        this.alertas = alertas;
        this.discrepancias = discrepancias;
        this.dispositivos = dispositivos;
        this.lecturas = lecturas;
        this.dispositivoService = dispositivoService;
    }

    @Transactional(readOnly = true)
    public ResumenDashboard resumen() {
        List<Dispositivo> activos = dispositivos.findByActivoTrue();
        long enLinea = activos.stream().filter(dispositivoService::enLinea).count();
        return new ResumenDashboard(
                productos.countByActivoTrue(),
                inventario.contarBajoMinimo(),
                alertas.countByEstadoNot(EstadoAlerta.CERRADA),
                discrepancias.countByEstado(EstadoDiscrepancia.PENDIENTE),
                enLinea,
                activos.size(),
                lecturas.contarDesde(OffsetDateTime.now().minusHours(1)));
    }
}

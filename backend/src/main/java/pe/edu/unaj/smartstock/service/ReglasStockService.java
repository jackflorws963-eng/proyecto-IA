package pe.edu.unaj.smartstock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Producto;
import pe.edu.unaj.smartstock.repository.InventarioRepository;

@Service
public class ReglasStockService {

    private final InventarioRepository inventario;
    private final AlertaService alertas;

    public ReglasStockService(InventarioRepository inventario, AlertaService alertas) {
        this.inventario = inventario;
        this.alertas = alertas;
    }

    @Transactional
    public void evaluarStockMinimo(Producto p) {
        String clave = AlertaService.clave(TipoAlerta.STOCK_MINIMO, "producto", p.getId());
        long total = inventario.stockTotal(p.getId());
        if (p.isActivo() && total < p.getStockMinimo()) {
            PrioridadAlerta prioridad = total == 0 ? PrioridadAlerta.ALTA : PrioridadAlerta.MEDIA;
            alertas.abrirOActualizar(TipoAlerta.STOCK_MINIMO, clave, prioridad,
                    "Stock de " + p.getCodigo() + " (" + p.getNombre() + ") = " + total
                            + ", minimo " + p.getStockMinimo());
        } else {
            alertas.cerrarSiExiste(clave, "stock normalizado (" + total + ")");
        }
    }
}

package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.PaginaResponse;
import pe.edu.unaj.smartstock.dto.Dtos.ProductoRequest;
import pe.edu.unaj.smartstock.dto.Dtos.ProductoResponse;
import pe.edu.unaj.smartstock.service.ProductoService;

@RestController
@RequestMapping("/api/productos")
public class ProductoController {

    private final ProductoService service;

    public ProductoController(ProductoService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaResponse<ProductoResponse> buscar(@RequestParam(defaultValue = "") String q,
                                                   @RequestParam(required = false) Long categoriaId,
                                                   @RequestParam(defaultValue = "false") boolean soloActivos,
                                                   @RequestParam(defaultValue = "0") int pagina,
                                                   @RequestParam(defaultValue = "20") int tamano) {
        return service.buscar(q, categoriaId, soloActivos, pagina, tamano);
    }

    @GetMapping("/{id}")
    public ProductoResponse obtener(@PathVariable Long id) { return service.obtener(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductoResponse crear(@Valid @RequestBody ProductoRequest r) { return service.crear(r); }

    @PutMapping("/{id}")
    public ProductoResponse editar(@PathVariable Long id, @Valid @RequestBody ProductoRequest r) {
        return service.editar(id, r);
    }

    @PatchMapping("/{id}/estado")
    public ProductoResponse estado(@PathVariable Long id, @RequestParam boolean activo) {
        return service.cambiarEstado(id, activo);
    }
}

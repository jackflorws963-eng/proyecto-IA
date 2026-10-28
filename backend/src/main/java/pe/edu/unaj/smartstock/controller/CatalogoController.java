package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.service.CatalogoService;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogoController {

    private final CatalogoService service;

    public CatalogoController(CatalogoService service) {
        this.service = service;
    }

    @GetMapping("/categorias")
    public List<CategoriaResponse> categorias() { return service.listarCategorias(); }

    @PostMapping("/categorias")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaResponse crearCategoria(@Valid @RequestBody CategoriaRequest r) { return service.crearCategoria(r); }

    @PutMapping("/categorias/{id}")
    public CategoriaResponse editarCategoria(@PathVariable Long id, @Valid @RequestBody CategoriaRequest r) {
        return service.editarCategoria(id, r);
    }

    @GetMapping("/ubicaciones")
    public List<UbicacionResponse> ubicaciones() { return service.listarUbicaciones(); }

    @PostMapping("/ubicaciones")
    @ResponseStatus(HttpStatus.CREATED)
    public UbicacionResponse crearUbicacion(@Valid @RequestBody UbicacionRequest r) { return service.crearUbicacion(r); }

    @PutMapping("/ubicaciones/{id}")
    public UbicacionResponse editarUbicacion(@PathVariable Long id, @Valid @RequestBody UbicacionRequest r) {
        return service.editarUbicacion(id, r);
    }
}

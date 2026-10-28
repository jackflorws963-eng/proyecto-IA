package pe.edu.unaj.smartstock;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "smartstock.seguridad.permitir-claves-desarrollo=true",
        "smartstock.seguridad.admin-password=Admin123!",
        "smartstock.seguridad.operador-password=Operador123!",
        "smartstock.ia.habilitada=false",
        "smartstock.seguridad.device-key=" + IntegracionApiTest.DEV_KEY})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_IT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegracionApiTest {

    static final String DEV_KEY = "dev-device-key-cambiar";

    @Autowired MockMvc mvc;

    @Test @Order(1)
    void sinSesionLaApiResponde401() throws Exception {
        mvc.perform(get("/api/productos")).andExpect(status().isUnauthorized());
    }

    @Test @Order(2)
    void loginCorrectoEIncorrecto() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin").param("password", "Admin123!"))
           .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin").param("password", "mala"))
           .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username", "admin").param("password", "Admin123!"))
           .andExpect(status().isForbidden());
    }

    @Test @Order(3) @WithMockUser(username = "operador", roles = "OPERADOR")
    void operadorNoPuedeCrearProductos() throws Exception {
        mvc.perform(post("/api/productos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"codigo":"X1","nombre":"X","categoriaId":1,"unidad":"u","precio":1,"stockMinimo":0}"""))
           .andExpect(status().isForbidden());
    }

    @Test @Order(4) @WithMockUser(username = "admin", roles = "ADMIN")
    void registraYConsultaProducto() throws Exception {
        mvc.perform(post("/api/productos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"codigo":"T100","nombre":"Tuercas test","categoriaId":1,"unidad":"bolsa",
                 "precio":2.5,"pesoUnitarioG":20,"stockMinimo":2}"""))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.codigo").value("T100"));
        mvc.perform(get("/api/productos").param("q", "tuercas"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.contenido[0].codigo").value("T100"));
        mvc.perform(post("/api/productos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"codigo":"t100","nombre":"Otro","categoriaId":1,"unidad":"u","precio":1,"stockMinimo":0}"""))
           .andExpect(status().isConflict());
        mvc.perform(post("/api/productos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"codigo":"T101","nombre":"Mal","categoriaId":1,"unidad":"u","precio":-1,"stockMinimo":0}"""))
           .andExpect(status().isBadRequest());
    }

    @Test @Order(5) @WithMockUser(username = "operador", roles = "OPERADOR")
    void entradaYSalidaSinStockNegativo() throws Exception {
        mvc.perform(post("/api/movimientos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipo":"ENTRADA","productoId":1,"ubicacionDestinoId":1,"cantidad":20,"motivo":"Compra inicial"}"""))
           .andExpect(status().isCreated());
        mvc.perform(post("/api/movimientos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipo":"SALIDA","productoId":1,"ubicacionOrigenId":1,"cantidad":500,"motivo":"Venta"}"""))
           .andExpect(status().isConflict());
        mvc.perform(post("/api/movimientos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipo":"SALIDA","productoId":1,"ubicacionOrigenId":1,"cantidad":-3,"motivo":"Venta"}"""))
           .andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventario").param("productoId", "1").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(20));
    }

    @Test @Order(6)
    void dispositivoSinClaveEsRechazado() throws Exception {
        mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Code", "ESP32-01").header("X-Device-Key", "clave-falsa")
                .content("{\"lecturas\":[{\"tipo\":\"TEMPERATURA\",\"valor\":20,\"seq\":1}]}"))
           .andExpect(status().isUnauthorized());
    }

    @Test @Order(7)
    void lecturasValidasDuplicadasYFueraDeRango() throws Exception {
        String lote = """
                {"lecturas":[
                  {"tipo":"TEMPERATURA","valor":21.5,"seq":1},
                  {"tipo":"HUMEDAD","valor":150,"seq":1},
                  {"tipo":"PESO","valor":1000,"seq":1}
                ]}""";
        mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Code", "ESP32-01").header("X-Device-Key", DEV_KEY).content(lote))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.guardadas").value(3))
           .andExpect(jsonPath("$.seqMaximo").value(1))
           .andExpect(jsonPath("$.resultados[1].detalle").value("FUERA_RANGO"));
        mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Code", "ESP32-01").header("X-Device-Key", DEV_KEY).content(lote))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.guardadas").value(0))
           .andExpect(jsonPath("$.duplicadas").value(3));
        mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Code", "ESP32-01").header("X-Device-Key", DEV_KEY)
                .content("{\"lecturas\":[{\"tipo\":\"TEMPERATURA\",\"valor\":30.25,\"seq\":1}]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reenviar").value(1))
           .andExpect(jsonPath("$.resultados[0].resultado").value("SEQ_REUTILIZADA"));
    }

    @Test @Order(8) @WithMockUser(username = "admin", roles = "ADMIN")
    void pesoQueNoCoincideGeneraDiscrepanciaYAlerta() throws Exception {
        mvc.perform(get("/api/discrepancias")).andExpect(jsonPath("$.length()").value(0));
        for (int seq = 2; seq <= 4; seq++) {
            mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Device-Code", "ESP32-01").header("X-Device-Key", DEV_KEY)
                    .content("{\"lecturas\":[{\"tipo\":\"PESO\",\"valor\":600,\"seq\":" + seq + "}]}"))
               .andExpect(status().isOk());
            mvc.perform(get("/api/discrepancias")).andExpect(jsonPath("$.length()").value(seq < 4 ? 0 : 1));
        }
        mvc.perform(get("/api/discrepancias"))
           .andExpect(jsonPath("$[0].estimacion.cantidadEstimada").value(12.0))
           .andExpect(jsonPath("$[0].estimacion.cantidadRegistrada").value(20));
        mvc.perform(get("/api/inventario").param("productoId", "1").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(20));
    }

    @Test @Order(9) @WithMockUser(username = "admin", roles = "ADMIN")
    void aprobarDiscrepanciaAjustaStockConLaEstimacionMasReciente() throws Exception {
        mvc.perform(post("/api/iot/lecturas").contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Code", "ESP32-01").header("X-Device-Key", DEV_KEY)
                .content("{\"lecturas\":[{\"tipo\":\"PESO\",\"valor\":500,\"seq\":5}]}"))
           .andExpect(status().isOk());
        String id = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/discrepancias")).andReturn().getResponse().getContentAsString(), "$[0].id").toString();
        mvc.perform(post("/api/discrepancias/" + id + "/aprobar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"Conteo fisico confirma 10\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.estado").value("APROBADA"));
        mvc.perform(get("/api/inventario").param("productoId", "1").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(10));
    }

    @Test @Order(10) @WithMockUser(username = "operador", roles = "OPERADOR")
    void ventaDescuentaStockYCalculaIgvYVuelto() throws Exception {
        mvc.perform(post("/api/ventas").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipoComprobante":"BOLETA","ubicacionId":1,"metodoPago":"EFECTIVO","montoRecibido":20,
                 "items":[{"productoId":1,"cantidad":2},{"productoId":1,"cantidad":1}]}"""))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.numero").value("B001-00000001"))
           .andExpect(jsonPath("$.total").value(10.50))
           .andExpect(jsonPath("$.subtotal").value(8.90))
           .andExpect(jsonPath("$.igv").value(1.60))
           .andExpect(jsonPath("$.vuelto").value(9.50))
           .andExpect(jsonPath("$.detalles.length()").value(1));
        mvc.perform(get("/api/inventario").param("productoId", "1").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(7));
        mvc.perform(post("/api/ventas").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipoComprobante":"BOLETA","ubicacionId":1,"metodoPago":"YAPE","items":[{"productoId":1,"cantidad":99}]}"""))
           .andExpect(status().isConflict());
        mvc.perform(post("/api/ventas").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"tipoComprobante":"FACTURA","ubicacionId":1,"metodoPago":"TARJETA","items":[{"productoId":1,"cantidad":1}]}"""))
           .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/ventas/1/anular").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"error\"}"))
           .andExpect(status().isForbidden());
    }

    @Test @Order(11) @WithMockUser(username = "admin", roles = "ADMIN")
    void anularVentaDevuelveElStock() throws Exception {
        mvc.perform(post("/api/ventas/1/anular").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Cliente devolvio la mercaderia\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.estado").value("ANULADA"));
        mvc.perform(get("/api/inventario").param("productoId", "1").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(10));
        mvc.perform(post("/api/ventas/1/anular").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"otra vez\"}"))
           .andExpect(status().isConflict());
    }

    @Test @Order(13) @WithMockUser(username = "operador", roles = "OPERADOR")
    void iaRespondeConsultasConElMotorPropio() throws Exception {
        mvc.perform(post("/api/ia/mensaje").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"cuanto vendimos hoy\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.motor").value("PROPIO"))
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("Hoy")));
        mvc.perform(post("/api/ia/mensaje").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"abre la pantalla de ventas\"}"))
           .andExpect(jsonPath("$.navegar").value("/ventas.html"));
        mvc.perform(get("/api/ia/estado")).andExpect(jsonPath("$.modeloDisponible").value(false));
    }

    @Test @Order(14) @WithMockUser(username = "operador", roles = "OPERADOR")
    void iaProponeYSoloEjecutaAlConfirmar() throws Exception {
        String r = mvc.perform(post("/api/ia/mensaje").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"registra una entrada de 5 P002 en Almacen A\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.porConfirmar.length()").value(1))
           .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/inventario").param("productoId", "2").param("ubicacionId", "1"))
           .andExpect(jsonPath("$.length()").value(0));
        String id = com.jayway.jsonpath.JsonPath.read(r, "$.porConfirmar[0].id");
        mvc.perform(post("/api/ia/confirmar/" + id).with(csrf()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("Registré")));
        mvc.perform(get("/api/inventario").param("productoId", "2").param("ubicacionId", "1"))
           .andExpect(jsonPath("$[0].cantidad").value(5));
        mvc.perform(post("/api/ia/confirmar/" + id).with(csrf()))
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("no está vigente")));
    }

    @Test @Order(15) @WithMockUser(username = "operador", roles = "OPERADOR")
    void iaAprendeYRecuerda() throws Exception {
        mvc.perform(post("/api/ia/mensaje").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"recuerda que el proveedor del arroz es Molinos del Sur\"}"))
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("Molinos del Sur")));
        mvc.perform(get("/api/ia/memoria")).andExpect(jsonPath("$[0].texto").value("el proveedor del arroz es Molinos del Sur"));
        mvc.perform(post("/api/ia/mensaje").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"quien es el proveedor del arroz\"}"))
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("Molinos del Sur")));
        mvc.perform(post("/api/ia/aprender").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"como anda la caja chica\",\"intencion\":\"resumen_ventas\"}"))
           .andExpect(jsonPath("$.texto").value(org.hamcrest.Matchers.containsString("Aprendido")));
        mvc.perform(get("/api/ia/estado")).andExpect(jsonPath("$.frasesAprendidas").value(1));
        mvc.perform(get("/api/ia/pronostico"))
           .andExpect(jsonPath("$[?(@.codigo == 'P001')].diasConDatos").value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test @Order(12)
    void saludDelServidorEsPublica() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}

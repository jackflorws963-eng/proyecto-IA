package pe.edu.unaj.smartstock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.edu.unaj.smartstock.config.Entorno;

@SpringBootApplication
@EnableScheduling
public class SmartStockApplication {
    public static void main(String[] args) {
        Entorno.registrarConfiguracionLocal();
        SpringApplication.run(SmartStockApplication.class, args);
    }
}

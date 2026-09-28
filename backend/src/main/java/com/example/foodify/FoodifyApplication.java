package com.example.foodify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class FoodifyApplication {
    public static void main(String[] args) {
        SpringApplication.run(FoodifyApplication.class, args);
    }
}

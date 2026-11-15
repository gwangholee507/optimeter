package com.compareopt.optvisualizer;

import com.compareopt.optvisualizer.desktop.DesktopApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OptVisualizerApplication {
    public static void main(String[] args) {
        // macOS 앱(.dmg)으로 열렸을 때는 Dock 아이콘, 브라우저 자동 열기 등을 붙여서 띄운다
        if (DesktopApp.isEnabled()) {
            DesktopApp.run(OptVisualizerApplication.class, args);
            return;
        }
        SpringApplication.run(OptVisualizerApplication.class, args);
    }
}

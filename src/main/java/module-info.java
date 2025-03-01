/**
 * Beispielhaftes module-info.java
 * Dieses Modul exportiert das Package "Converter" und benötigt 
 * die Module (bzw. automatischen Module) für Apache PDFBox 
 * und Apache POI (inkl. POI-OOXML).
 */
module com.example.pdfToExcel {

    // Die folgenden "requires"-Angaben können variieren, 
    // je nachdem wie Maven (oder dein Build-System) die JARs 
    // erkennt. PDFBox + POI bieten derzeit meist nur Automatic Modules an.

    requires org.apache.pdfbox;
    requires org.apache.poi.ooxml;
    requires org.apache.poi.poi;
    requires java.desktop;    // Automatischer Modulname (ggf. anpassen)

    // Falls nötig:
    // requires java.logging;     // z.B. wenn Logging-Klassen genutzt werden

}

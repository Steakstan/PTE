package com.example.pte.parser;

/**
 * Represents a model entry with its name and a flag indicating whether the model
 * should be highlighted in red (for example, when displayed in Excel).
 * Note: All UI-visible texts remain in German where applicable.
 */
public record ModelEntry(String modelName, boolean red) {
}

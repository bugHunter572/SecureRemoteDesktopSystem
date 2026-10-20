package com.srd.common.annotation;

/** Drawing tools of the annotation pen. Wire value = ordinal (append new tools at the end). */
public enum PenTool {
    PEN("Pen", false),
    HIGHLIGHTER("Highlighter", false),
    LINE("Line", true),
    ARROW("Arrow", true),
    RECTANGLE("Rectangle", true),
    ELLIPSE("Ellipse", true),
    LASER("Laser pointer", false),
    ERASER("Eraser", false),
    /** One point: where the text starts. The text and its size travel in the BEGIN message. */
    TEXT("Text", false),
    /** Two corners; the picture travels in the BEGIN message. */
    IMAGE("Image", true);

    public final String label;
    /** Shapes keep only their first and last point. */
    public final boolean shape;

    PenTool(String label, boolean shape) {
        this.label = label;
        this.shape = shape;
    }

    public static PenTool of(int ordinal) {
        PenTool[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : PEN;
    }
}

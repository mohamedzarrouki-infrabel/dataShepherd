package com.datashepherd.service;

import com.datashepherd.annotation.ExcelColumn;
import com.datashepherd.annotation.Image;
import com.datashepherd.annotation.Sheet;
import com.datashepherd.annotation.style.ExcelStyle;
import com.datashepherd.exception.StyleException;
import com.datashepherd.exception.WorkbookException;
import com.datashepherd.helper.writer.Elements;
import com.datashepherd.helper.writer.InitiateExcelStructure;
import com.datashepherd.helper.writer.model.FormatHandler;
import com.datashepherd.helper.writer.model.SheetPictureHandler;
import com.datashepherd.helper.writer.style.ExcelStyleHandler;
import com.datashepherd.helper.writer.style.condional.Registry;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Writer class extends Excel class and is responsible for writing data to an Excel sheet.
 * It uses Apache POI library to interact with Excel files.
 *
 * @param <T> the type of objects that this writer will write to the Excel sheet.
 */
public class Writer<T>  implements ExcelStyleHandler {
    private final Workbook workbook;
    private final Elements elements;
    private org.apache.poi.ss.usermodel.Sheet sheet;
    private final List<T> sources;
    private final Logger logger = Logger.getLogger(this.getClass().getName());
    private static final String MESSAGE = "Failed write the value of the field : {0}";
    private final Registry registry;
    private final FormatHandler formatHandler;
    private final Map<Class<Object>,List<Object>> writers = new HashMap<>();
    /**
     * Constructor for the Writer class.
     *
     * @param sources      the list of objects to be written to the Excel sheet.
     * @param entityClass  the class of the objects to be written to the Excel sheet.
     */
    public Writer(List<T> sources, Class<T> entityClass, Workbook workbook) {
        this.registry = new Registry();
        this.sources = sources;
        this.workbook = workbook;
        this.elements = header(entityClass);
        this.formatHandler = FormatHandler.getInstance(workbook);
    }

    /**
     * Writes the data from the sources to the Excel sheet.
     */
    protected void write() {
        sources.forEach(source -> writeLine(source, sheet.createRow(sheet.getLastRowNum() + 1)));
        registry.execute();
        registry.clear();
        writers.forEach((objectClass, objects) -> new Writer<>(objects, objectClass,workbook).write());
    }

    /**
     * Returns the sheet with the given name. If the sheet does not exist, a new one is created.
     *
     * @param name  the name of the sheet.
     * @return      the sheet with the given name.
     */
    private org.apache.poi.ss.usermodel.Sheet sheet(String name) {
        if(Objects.isNull(workbook)) throw new WorkbookException("Workbook is not initialized");
        if (Objects.nonNull(workbook.getSheet(name))) return workbook.getSheet(name);
        else return workbook.createSheet(name);
    }

    /**
     * Sets the header of the sheet at the given position.
     *
     * @param value     the value of the header.
     * @param position  the position of the header.
     * @param sheet     the sheet where the header is to be set.
     */
    private void sheetHeader(String value, Position position, org.apache.poi.ss.usermodel.Sheet sheet) {
        if (StringUtils.isNotBlank(value)) {
            switch (position) {
                case CENTER -> sheet.getHeader().setCenter(value);
                case LEFT -> sheet.getHeader().setLeft(value);
                case RIGHT -> sheet.getHeader().setRight(value);
            }
        }
    }

    /**
     * Sets the footer of the sheet at the given position.
     *
     * @param value     the value of the footer.
     * @param position  the position of the footer.
     * @param sheet     the sheet where the footer is to be set.
     */
    private void sheetFooter(String value, Position position, org.apache.poi.ss.usermodel.Sheet sheet) {
        if (StringUtils.isNotBlank(value)) {
            switch (position) {
                case CENTER -> sheet.getFooter().setCenter(value);
                case LEFT -> sheet.getFooter().setLeft(value);
                case RIGHT -> sheet.getFooter().setRight(value);
            }
        }
    }

    /**
     * Enum for the positions of the header and footer.
     */
    private enum Position {
        CENTER,
        LEFT,
        RIGHT
    }

    /**
     * Returns the elements of the header of the sheet.
     *
     * @param entityClass  the class of the objects to be written to the Excel sheet.
     * @return             the elements of the header of the sheet.
     */
    private Elements header(Class<T> entityClass) {
        Sheet sheetAttributes = entityClass.getAnnotation(Sheet.class);
        if (Objects.isNull(sheetAttributes)) throw new UnsupportedOperationException("unsupported class that not use the Sheet annotation");
        sheet = sheet(StringUtils.isBlank(sheetAttributes.name()) ? entityClass.getSimpleName() : sheetAttributes.name());
        if(!(workbook instanceof SXSSFWorkbook)) {
            sheetHeader(sheetAttributes.centerHeader(), Position.CENTER, sheet);
            sheetHeader(sheetAttributes.leftHeader(), Position.LEFT, sheet);
            sheetHeader(sheetAttributes.rightHeader(), Position.RIGHT, sheet);

            sheetFooter(sheetAttributes.centerFooter(), Position.CENTER, sheet);
            sheetFooter(sheetAttributes.leftFooter(), Position.LEFT, sheet);
            sheetFooter(sheetAttributes.rightFooter(), Position.RIGHT, sheet);
            new SheetPictureHandler(sheet, entityClass);
            registry.onComplete(()-> elements.structures().forEach(structure -> {
                sheet.autoSizeColumn(structure.order());
                sheet.setDefaultColumnWidth(structure.order());
            }));
        }
        return new InitiateExcelStructure(registry, Objects.requireNonNull(sheet), entityClass).getElements();
    }

    /**
     * Writes a line to the Excel sheet.
     * @param o         the object to be written to the Excel sheet.
     * @param cells     the cells of the row where the line is to be written.
     */
    protected void writeLine(Object o, org.apache.poi.ss.usermodel.Row cells) {
        processElements(o,cells);
        processChild(o);
    }

    private void processElements(Object o, org.apache.poi.ss.usermodel.Row cells) {
        var clazz = o.getClass();
        elements.structures().forEach(structure -> {
            try {
                Field field = clazz.getDeclaredField(structure.name());
                field.setAccessible(true);
                var value = field.get(o);
                var cell = cells.createCell(structure.order());
                structure.processor().accept(cell, field.isAnnotationPresent(Image.class)? Pair.of(field.getAnnotation(Image.class),value):value);
                elements.conditional().stream()
                        .filter(conditional -> conditional.name().equals(structure.name()))
                        .forEach(conditional -> conditional.processor().accept(cell, value));
                ExcelColumn column = field.getAnnotation(ExcelColumn.class);
                if ((StringUtils.isNotBlank(column.format()) || field.isAnnotationPresent(ExcelStyle.class))) {
                registry.onBefore(() -> {
                        CellStyle style = workbook.createCellStyle();
                        Font font = workbook.createFont();
                        cell.setCellStyle(style);
                        CellStyle cellStyle = cell.getCellStyle();
                        try {
                            cellStyle.setDataFormat(formatHandler.getFormat(column.format()));
                        } catch (Exception e) {
                            throw new StyleException("Failed to apply format", e);
                        }
                        applyStyles(field,cellStyle,font);
                });
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.log(Level.INFO, MESSAGE, structure.name());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processChild(Object o){
        var clazz = o.getClass();
        elements.children().forEach(child -> {
            try {
                Field field = clazz.getDeclaredField(child.name());
                field.setAccessible(true);
                var value = field.get(o);
                if (Object.class.isAssignableFrom(clazz) && value instanceof Collection<?> list) {
                    List<Object> sub = writers.getOrDefault(child.mappedBy(), new ArrayList<>());
                    sub.addAll(list.stream()
                            .map(Object.class::cast)
                            .collect(Collectors.toCollection(ArrayList::new)));
                    writers.put((Class<Object>) child.mappedBy(),sub);
                } else if (Object.class.isAssignableFrom(clazz) && Objects.nonNull(value)) {
                    List<Object> sub = writers.getOrDefault(child.mappedBy(), new ArrayList<>());
                    sub.add(value);
                    writers.put((Class<Object>) child.mappedBy(),sub);
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.log(Level.INFO, MESSAGE, child.name());
            }
        });
    }

    private void applyStyles(Field field, CellStyle style, Font font) {
        if (field.isAnnotationPresent(ExcelStyle.class)) createStyle(field.getAnnotation(ExcelStyle.class), style, font);
    }
}

package com.qcadoo.mes.products.print;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.MalformedURLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.krysalis.barcode4j.impl.code128.Code128Bean;
import org.krysalis.barcode4j.output.bitmap.BitmapCanvasProvider;
import org.krysalis.barcode4j.tools.UnitConv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lowagie.text.BadElementException;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.qcadoo.mes.api.Entity;
import com.qcadoo.mes.api.SecurityService;
import com.qcadoo.mes.api.TranslationService;
import com.qcadoo.mes.beans.users.UsersUser;
import com.qcadoo.mes.internal.EntityTree;
import com.qcadoo.mes.internal.EntityTreeNode;
import com.qcadoo.mes.model.types.internal.DateType;
import com.qcadoo.mes.products.util.EntityNumberComparator;
import com.qcadoo.mes.products.util.EntityOperationNumberComparator;
import com.qcadoo.mes.products.util.EntityOrderNumberComparator;
import com.qcadoo.mes.products.util.SortUtil;
import com.qcadoo.mes.utils.Pair;
import com.qcadoo.mes.utils.pdf.PdfUtil;

@Service
public class ProductReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductReportService.class);

    private static final SimpleDateFormat D_F = new SimpleDateFormat(DateType.DATE_FORMAT);

    private final int[] defaultWorkPlanColumnWidth = new int[] { 20, 20, 20, 13, 13, 13 };

    private final int[] defaultWorkPlanOperationColumnWidth = new int[] { 10, 21, 23, 23, 23 };

    @Autowired
    private TranslationService translationService;

    @Autowired
    private SecurityService securityService;

    private static final String MATERIAL_COMPONENT = "01component";

    private static final String MATERIAL_WASTE = "04waste";

    private static final String COMPONENT_QUANTITY_ALGORITHM = "02perTechnology";

    public final Map<Entity, BigDecimal> getTechnologySeries(final Entity entity, final List<Entity> orders) {
        Map<Entity, BigDecimal> products = new HashMap<Entity, BigDecimal>();
        for (Entity component : orders) {
            Entity order = (Entity) component.getField("order");
            Entity technology = (Entity) order.getField("technology");
            BigDecimal plannedQuantity = (BigDecimal) order.getField("plannedQuantity");
            if (technology != null && plannedQuantity != null && plannedQuantity.compareTo(BigDecimal.ZERO) > 0) {
                EntityTree operationComponents = technology.getTreeField("operationComponents");
                if (COMPONENT_QUANTITY_ALGORITHM.equals(technology.getField("componentQuantityAlgorithm"))) {
                    countQuntityComponentPerTechnology(products, operationComponents,
                            (Boolean) entity.getField("onlyComponents"), plannedQuantity);
                } else {
                    Map<Entity, BigDecimal> orderProducts = new HashMap<Entity, BigDecimal>();
                    boolean success = countQuntityComponentPerOutProducts(orderProducts, operationComponents.getRoot(),
                            (Boolean) entity.getField("onlyComponents"), plannedQuantity);
                    if (success) {
                        // TODO krna
                    }
                }
            }
        }
        return products;
    }

    private void countQuntityComponentPerTechnology(final Map<Entity, BigDecimal> products,
            final List<Entity> operationComponents, final boolean onlyComponents, final BigDecimal plannedQuantity) {
        for (Entity operationComponent : operationComponents) {
            List<Entity> operationProductComponents = operationComponent.getHasManyField("operationProductInComponents");
            for (Entity operationProductComponent : operationProductComponents) {
                Entity product = (Entity) operationProductComponent.getField("product");
                if (!onlyComponents || MATERIAL_COMPONENT.equals(product.getField("typeOfMaterial"))) {
                    if (products.containsKey(product)) {
                        BigDecimal quantity = products.get(product);
                        quantity = ((BigDecimal) operationProductComponent.getField("quantity")).multiply(plannedQuantity).add(
                                quantity);
                        products.put(product, quantity);
                    } else {
                        products.put(product,
                                ((BigDecimal) operationProductComponent.getField("quantity")).multiply(plannedQuantity));
                    }
                }
            }
        }
    }

    private boolean countQuntityComponentPerOutProducts(final Map<Entity, BigDecimal> products, final EntityTreeNode node,
            final boolean onlyComponents, final BigDecimal plannedQuantity) {
        Entity productOutComponent = checkOutProducts(node);
        if (productOutComponent == null) {
            return false;
        }
        List<Entity> operationProductInComponents = node.getHasManyField("operationProductInComponents");
        if (operationProductInComponents.size() == 0) {
            return false;
        }
        for (Entity operationProductInComponent : operationProductInComponents) {
            Entity product = (Entity) operationProductInComponent.getField("product");
            if (!(Boolean) onlyComponents || MATERIAL_COMPONENT.equals(product.getField("typeOfMaterial"))) {
                Entity prevOperationOutProduct = findPrevOperationOutProduct(node, product);
                BigDecimal quantity = new BigDecimal("0");
                if (prevOperationOutProduct == null) {
                    quantity = ((BigDecimal) operationProductInComponent.getField("quantity")).multiply(plannedQuantity,
                            MathContext.DECIMAL128).divide((BigDecimal) productOutComponent.getField("quantity"),
                            MathContext.DECIMAL128);
                } else {
                    quantity = ((BigDecimal) operationProductInComponent.getField("quantity"))
                            .multiply(plannedQuantity, MathContext.DECIMAL128)
                            .divide((BigDecimal) productOutComponent.getField("quantity"), MathContext.DECIMAL128)
                            .divide((BigDecimal) prevOperationOutProduct.getField("quantity"));
                }
                if (products.containsKey(product)) {
                    products.put(product, products.get(product).add(quantity));
                } else {
                    products.put(product, quantity);
                }
            }
        }
        // TODO krna rekurencja
        return true;
    }

    private Entity checkOutProducts(final Entity operationComponent) {
        List<Entity> operationProductOutComponents = operationComponent.getHasManyField("operationProductOutComponents");
        Entity productOutComponent = null;
        if (operationProductOutComponents.size() == 0) {
            return null;
        } else {
            int productCount = 0;
            for (Entity operationProductOutComponent : operationProductOutComponents) {
                Entity product = (Entity) operationProductOutComponent.getField("product");
                if (!MATERIAL_WASTE.equals(product.getField("typeOfMaterial"))) {
                    productOutComponent = operationProductOutComponent;
                    productCount++;
                }
            }
            if (productCount != 1) {
                return null;
            }
        }
        return productOutComponent;
    }

    private Entity findPrevOperationOutProduct(final EntityTreeNode node, final Entity product) {
        List<EntityTreeNode> children = node.getChildren();
        for (Entity operationComponent : children) {
            List<Entity> operationProductOutComponents = operationComponent.getHasManyField("operationProductOutComponents");
            for (Entity operationProductOutComponent : operationProductOutComponents) {
                Entity productOut = (Entity) operationProductOutComponent.getField("product");
                if (!MATERIAL_WASTE.equals(product.getField("typeOfMaterial"))
                        && productOut.getField("number").equals(product.getField("number"))) {
                    return operationProductOutComponent;
                }
            }
        }
        return null;
    }

    private Map<Entity, Map<Entity, List<Entity>>> getOperationSeries(final Entity entity, final String type) {
        Map<Entity, Map<Entity, List<Entity>>> operations = new HashMap<Entity, Map<Entity, List<Entity>>>();
        List<Entity> orders = entity.getHasManyField("orders");
        for (Entity component : orders) {
            Entity order = (Entity) component.getField("order");
            Entity technology = (Entity) order.getField("technology");
            if (technology != null) {
                EntityTree operationComponents = technology.getTreeField("operationComponents");

                Entity entityKey = null;

                if (type.equals("product")) {
                    Entity product = (Entity) order.getField("product");
                    entityKey = product;
                }

                for (Entity operationComponent : operationComponents) {
                    Entity operation = (Entity) operationComponent.getField("operation");

                    if (type.equals("machine")) {
                        Object machine = operation.getField("machine");
                        if (machine != null) {
                            entityKey = (Entity) machine;
                        }
                    } else if (type.equals("worker")) {
                        Object machine = operation.getField("staff");
                        if (machine != null) {
                            entityKey = (Entity) machine;
                        }
                    }
                    if (operations.containsKey(entityKey)) {
                        Map<Entity, List<Entity>> operationMap = operations.get(entityKey);
                        List<Entity> ordersList;
                        if (operationMap.containsKey(operationComponent)) {
                            ordersList = operationMap.get(operationComponent);
                        } else {
                            ordersList = new ArrayList<Entity>();
                        }
                        ordersList.add(order);
                        operationMap.put(operationComponent, ordersList);
                    } else {
                        Map<Entity, List<Entity>> operationMap = new HashMap<Entity, List<Entity>>();
                        List<Entity> ordersList = new ArrayList<Entity>();
                        ordersList.add(order);
                        operationMap.put(operationComponent, ordersList);
                        operations.put(entityKey, operationMap);
                    }
                }
            }
        }
        return operations;
    }

    private void getOperationSeries(final Entity entity, final String type, final String algorithm) {
        Map<Entity, Map<Entity, Pair<BigDecimal, List<Entity>>>> operations = new HashMap<Entity, Map<Entity, Pair<BigDecimal, List<Entity>>>>();
        List<Entity> orders = entity.getHasManyField("orders");
        for (Entity component : orders) {
            Entity order = (Entity) component.getField("order");
            Entity technology = (Entity) order.getField("technology");
            if (technology != null) {
                EntityTree operationComponents = technology.getTreeField("operationComponents");
                aggregateTreeData(operationComponents.getRoot(), operations, type, order);
            }
        }
    }

    private void aggregateTreeData(final EntityTreeNode node,
            final Map<Entity, Map<Entity, Pair<BigDecimal, List<Entity>>>> operations, final String type, final Entity order) {
        Entity entityKey = null;
        Entity operation = (Entity) node.getField("operation");
        List<Entity> operationProductInComponents = node.getHasManyField("operationProductInComponents");
        if (operationProductInComponents.size() == 0) {
            return;
        }
        Entity productOutComponent = checkOutProducts(node);
        if (productOutComponent == null) {
            return;
        }

        if (type.equals("product")) {
            Entity product = (Entity) order.getField("product");
            entityKey = product;
        }

        if (type.equals("machine")) {
            Object machine = operation.getField("machine");
            if (machine != null) {
                entityKey = (Entity) machine;
            }
        } else if (type.equals("worker")) {
            Object machine = operation.getField("staff");
            if (machine != null) {
                entityKey = (Entity) machine;
            }
        }

        if (operations.containsKey(entityKey)) {
            Map<Entity, Pair<BigDecimal, List<Entity>>> operationMap = operations.get(entityKey);
            List<Entity> ordersList;
            BigDecimal quantity;
            if (operationMap.containsKey(node)) {
                Pair<BigDecimal, List<Entity>> pair = operationMap.get(node);
                ordersList = pair.getValue();
                quantity = pair.getKey();
            } else {
                ordersList = new ArrayList<Entity>();
                quantity = new BigDecimal("0");
            }
            ordersList.add(order);
            Pair<BigDecimal, List<Entity>> pair = Pair.of(quantity, ordersList);
            operationMap.put(node, pair);
        } else {
            Map<Entity, Pair<BigDecimal, List<Entity>>> operationMap = new HashMap<Entity, Pair<BigDecimal, List<Entity>>>();
            List<Entity> ordersList = new ArrayList<Entity>();
            ordersList.add(order);
            Pair<BigDecimal, List<Entity>> pair = Pair.of(((BigDecimal) order.getField("plannedQuantity")), ordersList);
            operationMap.put(node, pair);
            operations.put(entityKey, operationMap);
        }

    }

    public void addOperationSeries(final Document document, final Entity entity, final Locale locale, final String type)
            throws DocumentException {
        DecimalFormat decimalFormat = (DecimalFormat) DecimalFormat.getInstance(locale);
        decimalFormat.setMaximumFractionDigits(3);
        decimalFormat.setMinimumFractionDigits(3);
        boolean firstPage = true;
        Map<Entity, Map<Entity, List<Entity>>> operations = getOperationSeries(entity, type);
        for (Entry<Entity, Map<Entity, List<Entity>>> entry : operations.entrySet()) {
            if (!firstPage) {
                document.newPage();
            }

            PdfPTable orderTable = PdfUtil.createTableWithHeader(6, getOrderHeader(document, entity, locale), false,
                    defaultWorkPlanColumnWidth);

            if (type.equals("machine") || type.equals("worker")) {
                addOrderSeries(orderTable, entity, decimalFormat);
                document.add(orderTable);
                document.add(Chunk.NEWLINE);
            }

            if (type.equals("machine")) {
                Entity machine = entry.getKey();
                Paragraph title = new Paragraph(new Phrase(translationService.translate("products.workPlan.report.paragrah3",
                        locale), PdfUtil.getArialBold11Light()));
                String name = "";
                if (machine != null) {
                    name = machine.getField("name").toString();
                }
                title.add(new Phrase(" " + name, PdfUtil.getArialBold19Dark()));
                document.add(title);
            } else if (type.equals("worker")) {
                Entity staff = entry.getKey();
                Paragraph title = new Paragraph(new Phrase(translationService.translate("products.workPlan.report.paragrah2",
                        locale), PdfUtil.getArialBold11Light()));
                String name = "";
                if (staff != null) {
                    name = staff.getField("name") + " " + staff.getField("surname");
                }
                title.add(new Phrase(" " + name, PdfUtil.getArialBold19Dark()));
                document.add(title);
            } else if (type.equals("product")) {
                Entity product = entry.getKey();
                Paragraph title = new Paragraph(new Phrase(translationService.translate("products.workPlan.report.paragrah4",
                        locale), PdfUtil.getArialBold11Light()));

                Map<Entity, List<Entity>> values = entry.getValue();
                List<List<Entity>> orders = new ArrayList<List<Entity>>(values.values());

                BigDecimal totalQuantity = new BigDecimal(0.0);

                Set<String> addedOrders = new HashSet<String>();

                for (List<Entity> singleOrderList : orders) {
                    for (Entity singleOrder : singleOrderList) {
                        if (!addedOrders.contains(singleOrder.getField("name").toString())) {
                            totalQuantity = totalQuantity.add((BigDecimal) singleOrder.getField("plannedQuantity"));
                            addedOrders.add(singleOrder.getField("name").toString());
                        }
                    }
                }

                addOrderSeries(orderTable, orders, decimalFormat);

                document.add(orderTable);
                document.add(Chunk.NEWLINE);

                title.add(new Phrase(" " + totalQuantity + " x " + product.getField("name"), PdfUtil.getArialBold19Dark()));
                document.add(title);

            }
            PdfPTable table = PdfUtil.createTableWithHeader(5, getOperationHeader(locale), false,
                    defaultWorkPlanOperationColumnWidth);

            table.getDefaultCell().setVerticalAlignment(Element.ALIGN_TOP);

            Map<Entity, List<Entity>> operationMap = SortUtil.sortMapUsingComparator(entry.getValue(),
                    new EntityOperationNumberComparator());

            for (Entry<Entity, List<Entity>> entryComponent : operationMap.entrySet()) {

                List<Entity> entryComponents = entryComponent.getValue();

                for (Entity singleOperationComponent : entryComponents) {
                    Entity operation = (Entity) entryComponent.getKey().getField("operation");
                    table.addCell(new Phrase(operation.getField("number").toString(), PdfUtil.getArialRegular9Dark()));
                    table.addCell(new Phrase(operation.getField("name").toString(), PdfUtil.getArialRegular9Dark()));
                    table.addCell(new Phrase(singleOperationComponent.getField("number").toString(), PdfUtil
                            .getArialRegular9Dark()));
                    List<Entity> operationProductOutComponents = entryComponent.getKey().getHasManyField(
                            "operationProductOutComponents");
                    List<Entity> operationProductInComponents = entryComponent.getKey().getHasManyField(
                            "operationProductInComponents");
                    addProductSeries(table, operationProductOutComponents, decimalFormat, singleOperationComponent);
                    addProductSeries(table, operationProductInComponents, decimalFormat, singleOperationComponent);
                }
            }
            document.add(table);
            firstPage = false;
        }
    }

    private Image generateBarcode(final String code) throws BadElementException {
        Code128Bean codeBean = new Code128Bean();
        final int dpi = 150;

        codeBean.setModuleWidth(UnitConv.in2mm(1.0f / dpi));
        codeBean.doQuietZone(false);
        codeBean.setHeight(8);
        codeBean.setFontSize(0.0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BitmapCanvasProvider canvas = new BitmapCanvasProvider(out, "image/x-png", dpi, BufferedImage.TYPE_BYTE_BINARY,
                    false, 0);

            codeBean.generateBarcode(canvas, code);

            canvas.finish();
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
        }

        try {
            Image image = Image.getInstance(out.toByteArray());
            image.setAlignment(Image.RIGHT);

            return image;
        } catch (MalformedURLException e) {
            LOG.error(e.getMessage(), e);
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }

        return null;
    }

    private void addProductSeries(final PdfPTable table, final List<Entity> operationProductComponents, final DecimalFormat df,
            final Entity entity) {
        StringBuilder products = new StringBuilder();
        for (Entity operationProductComponent : operationProductComponents) {
            Entity product = (Entity) operationProductComponent.getField("product");

            BigDecimal quantity = (BigDecimal) operationProductComponent.getField("quantity");
            BigDecimal plannedQuantity = (BigDecimal) entity.getField("plannedQuantity");
            BigDecimal totalQuantity = quantity.multiply(plannedQuantity);

            products.append(product.getField("number").toString() + " " + product.getField("name").toString() + " x "
                    + df.format(totalQuantity) + " ["
                    + (product.getField("unit") != null ? product.getField("unit").toString() : "") + "] \n\n");

        }
        table.addCell(new Phrase(products.toString(), PdfUtil.getArialRegular9Dark()));
    }

    private List<String> getOrderHeader(final Document document, final Entity entity, final Locale locale)
            throws DocumentException {
        String documenTitle = translationService.translate("products.workPlan.report.title", locale);
        String documentAuthor = translationService.translate("products.materialRequirement.report.author", locale);
        UsersUser user = securityService.getCurrentUser();
        PdfUtil.addDocumentHeader(document, entity.getField("name").toString(), documenTitle, documentAuthor,
                (Date) entity.getField("date"), user);
        document.add(generateBarcode(entity.getField("name").toString()));
        document.add(new Paragraph(translationService.translate("products.workPlan.report.paragrah", locale), PdfUtil
                .getArialBold11Dark()));
        List<String> orderHeader = new ArrayList<String>();
        orderHeader.add(translationService.translate("products.order.number.label", locale));
        orderHeader.add(translationService.translate("products.order.name.label", locale));
        orderHeader.add(translationService.translate("products.order.product.label", locale));
        orderHeader.add(translationService.translate("products.order.plannedQuantity.label", locale));
        orderHeader.add(translationService.translate("products.product.unit.label", locale));
        orderHeader.add(translationService.translate("products.order.dateTo.label", locale));
        return orderHeader;
    }

    private List<String> getOperationHeader(final Locale locale) {
        List<String> operationHeader = new ArrayList<String>();
        operationHeader.add(translationService.translate("products.operation.number.label", locale));
        operationHeader.add(translationService.translate("products.operation.name.label", locale));
        operationHeader.add(translationService.translate("products.workPlan.report.operationTable.order.column", locale));
        operationHeader.add(translationService.translate("products.workPlan.report.operationTable.productsOut.column", locale));
        operationHeader.add(translationService.translate("products.workPlan.report.operationTable.productsIn.column", locale));
        return operationHeader;
    }

    private void addOrderSeries(final PdfPTable table, final Entity entity, final DecimalFormat df) throws DocumentException {
        List<Entity> orders = new ArrayList<Entity>(entity.getHasManyField("orders"));
        Collections.sort(orders, new EntityOrderNumberComparator());
        for (Entity component : orders) {
            Entity order = (Entity) component.getField("order");
            table.addCell(new Phrase(order.getField("number").toString(), PdfUtil.getArialRegular9Dark()));
            table.addCell(new Phrase(order.getField("name").toString(), PdfUtil.getArialRegular9Dark()));
            Entity product = (Entity) order.getField("product");
            if (product != null) {
                table.addCell(new Phrase(product.getField("name").toString(), PdfUtil.getArialRegular9Dark()));
            } else {
                table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
            }
            table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_RIGHT);
            BigDecimal plannedQuantity = (BigDecimal) order.getField("plannedQuantity");
            plannedQuantity = (plannedQuantity == null) ? BigDecimal.ZERO : plannedQuantity;
            table.addCell(new Phrase(df.format(plannedQuantity), PdfUtil.getArialRegular9Dark()));
            table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_LEFT);
            if (product != null) {
                Object unit = product.getField("unit");
                if (unit != null) {
                    table.addCell(new Phrase(unit.toString(), PdfUtil.getArialRegular9Dark()));
                } else {
                    table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
                }
            } else {
                table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
            }
            table.addCell(new Phrase(D_F.format((Date) order.getField("dateTo")), PdfUtil.getArialRegular9Dark()));
        }
    }

    private void addOrderSeries(final PdfPTable table, final List<List<Entity>> values, final DecimalFormat df)
            throws DocumentException {

        Set<String> added = new HashSet<String>();

        for (List<Entity> entry : values) {
            Collections.sort(entry, new EntityNumberComparator());
            for (Entity order : entry) {
                if (!added.contains(order.getField("name").toString())) {
                    table.addCell(new Phrase(order.getField("number").toString(), PdfUtil.getArialRegular9Dark()));
                    table.addCell(new Phrase(order.getField("name").toString(), PdfUtil.getArialRegular9Dark()));
                    Entity product = (Entity) order.getField("product");
                    if (product != null) {
                        table.addCell(new Phrase(product.getField("name").toString(), PdfUtil.getArialRegular9Dark()));
                    } else {
                        table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
                    }
                    table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_RIGHT);
                    BigDecimal plannedQuantity = (BigDecimal) order.getField("plannedQuantity");
                    plannedQuantity = (plannedQuantity == null) ? BigDecimal.ZERO : plannedQuantity;
                    table.addCell(new Phrase(df.format(plannedQuantity), PdfUtil.getArialRegular9Dark()));
                    table.getDefaultCell().setHorizontalAlignment(Element.ALIGN_LEFT);
                    if (product != null) {
                        Object unit = product.getField("unit");
                        if (unit != null) {
                            table.addCell(new Phrase(unit.toString(), PdfUtil.getArialRegular9Dark()));
                        } else {
                            table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
                        }
                    } else {
                        table.addCell(new Phrase("", PdfUtil.getArialRegular9Dark()));
                    }
                    table.addCell(new Phrase(D_F.format((Date) order.getField("dateTo")), PdfUtil.getArialRegular9Dark()));
                }
                added.add(order.getField("name").toString());
            }
        }
    }

}

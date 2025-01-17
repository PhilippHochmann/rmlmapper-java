package be.ugent.rml.records.classDiagram;

import org.w3c.dom.Node;

import java.util.LinkedList;
import java.util.List;

public class CdClass extends CdElement {

    class Rectangle {
        double x;
        double y;
        double width;
        double height;

        Rectangle(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    String name;
    boolean isInterface;
    CdClass base;
    double separatorY;
    List<CdClass> children;
    List<CdArrow> uses;
    List<CdArrow> usedBy;
    List<CdAttribute> attributes;
    Rectangle rect;

    public CdClass(Node node, boolean isInterface) {
        super(node);
        this.name = node.getAttributes().getNamedItem("value").getTextContent();
        this.isInterface = isInterface;
        this.children = new LinkedList<>();
        this.uses = new LinkedList<>();
        this.usedBy = new LinkedList<>();
        this.attributes = new LinkedList<>();
        this.node = node;
        this.rect = null;
        extractRectangle();
    }

    public void addChild(CdClass child) {
        children.add(child);
        child.base = this;
    }

    public void addUse(CdArrow use) {
        uses.add(use);
        use.target.usedBy.add(use);
    }

    public void addAttribute(CdAttribute attr) {
        attributes.add(attr);
        attr.clazz = this;
    }

    public CdAttributeType getAttributeType(double y) {
        if (y < separatorY) {
            // It's a field
            return CdAttributeType.CD_FIELD;
        } else {
            // It's a function
            return CdAttributeType.CD_FUNCTION;
        }
    }

    public void setSeparatorY(double y) {
        this.separatorY = y;
    }

    public String get(String ref) throws Exception {
        if (ref.equals("id")) {
            return id;
        } else {
            if (ref.equals("name")) {
                return name;
            } else {
                if (ref.equals("isInterface")) {
                    return isInterface ? "true" : "false";
                } else {
                    if (ref.startsWith("base.")) {
                        if (base == null) return "NULL";
                        return base.get(ref.substring(ref.indexOf(".") + 1));
                    } else {
                        throw new Exception("Invalid reference for class.");
                    }
                }
            }
        }
    }

    private void extractRectangle() {
        Node geometry = CdUtils.getChildNode(this.node, "mxGeometry");
        if (geometry == null) return;

        String x = CdUtils.getAttribute(geometry, "x");
        String y = CdUtils.getAttribute(geometry, "y");
        String width = CdUtils.getAttribute(geometry, "width");
        String height = CdUtils.getAttribute(geometry, "height");

        this.rect = new Rectangle(
                x != null ? Double.parseDouble(x) : 0,
                y != null ? Double.parseDouble(y) : 0,
                width != null ? Double.parseDouble(width) : 0,
                height != null ? Double.parseDouble(height) : 0);
    }

    public double getDistanceToBox(double x, double y) {
        if (this.rect == null) {
            System.out.println("Class " + name + " does not have a rectangle, can't compute distance");
            return Double.MAX_VALUE;
        }

        double dx = Math.max(Math.max(rect.x - x, 0), x - rect.x - rect.width);
        double dy = Math.max(Math.max(rect.y - y, 0), y - rect.y - rect.height);
        return Math.sqrt(dx * dx + dy * dy);
    }

    public List<CdClass> extractClasses(String selector) throws Exception {

        LinkedList<CdClass> res = new LinkedList<>();

        if (selector.equals("base")) {
            if (base != null) res.add(base);
        } else {
            if (selector.equals("children")) {
                res.addAll(children);
            } else {
                if (selector.equals("usedClasses")) {
                    for (CdArrow usage : uses) {
                        res.add(usage.target);
                    }
                } else {
                    if (selector.equals("userClasses")) {
                        for (CdArrow usedBy : usedBy) {
                            res.add(usedBy.source);
                        }
                    } else {
                        throw new Exception("ClassSelection step invalid: " + selector);
                    }
                }
            }
        }

        return res;
    }
}

// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.piclayer.layer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.openstreetmap.josm.actions.RenameLayerAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.geoimage.ImageEntry;
import org.openstreetmap.josm.plugins.piclayer.actions.LoadPictureCalibrationAction;
import org.openstreetmap.josm.plugins.piclayer.actions.LoadPictureCalibrationFromWorldAction;
import org.openstreetmap.josm.plugins.piclayer.actions.ResetCalibrationAction;
import org.openstreetmap.josm.plugins.piclayer.actions.SavePictureCalibrationAction;
import org.openstreetmap.josm.plugins.piclayer.actions.SavePictureCalibrationToWorldAction;
import org.openstreetmap.josm.plugins.piclayer.actions.autocalibrate.utils.GeoLine;
import org.openstreetmap.josm.plugins.piclayer.transform.PictureTransform;
import org.openstreetmap.josm.tools.JosmDecimalFormatSymbolsProvider;
import org.openstreetmap.josm.tools.Logging;

/**
 * Base class for layers showing images. Actually it does all the showing. The
 * subclasses are supposed only to create images in different ways (load from
 * files, copy from clipboard, hack into a spy satellite and download them,
 * anything...)
 */
public abstract class PicLayerAbstract extends Layer {

    // Keys for loading from old/new Properties
    private static final String POSITION_X = "POSITION_X";
    private static final String POSITION_Y = "POSITION_Y";
    private static final String ANGLE = "ANGLE";
    private static final String INITIAL_SCALE = "INITIAL_SCALE";
    private static final String SCALEX = "SCALEX";
    private static final String SCALEY = "SCALEY";
    private static final String SHEARX = "SHEARX";
    private static final String SHEARY = "SHEARY";
    // new properties
    private static final String MATRIXm00 = "M00";
    private static final String MATRIXm01 = "M01";
    private static final String MATRIXm10 = "M10";
    private static final String MATRIXm11 = "M11";
    private static final String MATRIXm02 = "M02";
    private static final String MATRIXm12 = "M12";

    // Counter - just for naming of layers
    private static int imageCounter = 0;

    // This is the main image to be displayed
    protected Image image = null;

    // Tiles of pin images
    private static Image pinTiledImage;
    private static Image pinTiledImageOrange;

    // save file for IO Sessions
    File imageFile;

    // Initial position of the image in the real world
    // protected EastNorth initialImagePosition;
    // Position of the image in the real world
    // protected EastNorth imagePosition
    // The scale that was set on the map during image creation

    protected double initialImageScale = 1.0;

    protected PictureTransform transformer;

    // Layer icon / lines
    private Icon layerIcon = null;

    protected final Projection projection;

    // pin images properties - tile anchors, width and offset
    // TODO: load these from properties file in images folder...
    private static final int pinAnchorX = 31;
    private static final int pinAnchorY = 31;
    private static final int[] pinTileOffsetX = {74, 0, 74, 0};
    private static final int[] pinTileOffsetY = {0, 74, 74, 0};
    private static final int pinWidth = 64;
    private static final int pinHeight = 64;

    // markers and usability values
    private boolean drawOriginMarkers = true;
    private boolean drawRefMarkers = false;
    private boolean drawOrigin1To2Line = false;
    private boolean drawOrigin2To3Line = false;
    private boolean drawRef1To2Line = false;
    private boolean drawRef2To3Line = false;
    private GeoLine refLine1To2;
    private GeoLine refLine2To3;

    /**
     * Constructor
     */
    public PicLayerAbstract() {
        super("PicLayer #" + imageCounter);

        //Increase number
        imageCounter++;

        // Load layer icon
        layerIcon = new ImageIcon(Toolkit.getDefaultToolkit().createImage(getClass().getResource("/images/layericon.png")));

        if (pinTiledImage == null) {
            // allow system to load the image and use it in future
            pinTiledImage = new ImageIcon(Toolkit.getDefaultToolkit().createImage(getClass().getResource("/images/v6_64.png"))).getImage();
            pinTiledImageOrange = new ImageIcon(Toolkit.getDefaultToolkit().createImage(getClass().getResource("/images/v6_64o.png"))).getImage();
        }

        projection = ProjectionRegistry.getProjection();
    }

    /**
     * Initializes the image. Gets the image from a subclass and stores some
     * initial parameters. Throws exception if something fails.
     *
     * @throws IOException in case of error
     */
    public void initialize() throws IOException {
        // First, we initialize the calibration, so that createImage() can rely on it

        if (transformer == null) transformer = new PictureTransform();

        // If the map does not exist - we're screwed. We should not get into this situation in the first place!
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {

            EastNorth center = MainApplication.getMap().mapView.getCenter();

            transformer.setImagePosition(new EastNorth(center.east(), center.north()));
            // Initial scale at which the image was loaded
            initialImageScale = MainApplication.getMap().mapView.getDist100Pixel();
        } else {
            throw new IOException(tr("Could not find the map object."));
        }

        // Create image
        image = createImage();
        if (image == null) {
            throw new IOException(tr("PicLayer failed to load or import the image."));
        }
        // Load image completely
        new ImageIcon(image).getImage();

        lookForCalibration();
    }

    /**
     * To be overridden by subclasses. Provides an image from an external sources.
     * Throws exception if something does not work.
     * <p>
     * TODO: Replace the IOException by our own exception.
     *
     * @return created image
     * @throws IOException in case of error
     */
    protected abstract Image createImage() throws IOException;

    protected abstract void lookForCalibration() throws IOException;

    @Override
    public boolean isMergable(Layer arg0) {
        return false;
    }

    @Override
    public void mergeFrom(Layer arg0) {
    }

    /**
     * To be overridden by subclasses. Returns the user readable name of the layer.
     *
     * @return the user readable name of the layer
     */
    public abstract String getPicLayerName();

    public Image getImage() {
        return this.image;
    }

    @Override
    public Icon getIcon() {
        return layerIcon;
    }

    @Override
    public Object getInfoComponent() {
        return null;
    }

    @Override
    public Action[] getMenuEntries() {
        // Main menu
        return new Action[]{
                new ResetCalibrationAction(this, transformer),
                SeparatorLayerAction.INSTANCE,
                new SavePictureCalibrationAction(this),
                new LoadPictureCalibrationAction(this),
                SeparatorLayerAction.INSTANCE,
                new SavePictureCalibrationToWorldAction(this),
                new LoadPictureCalibrationFromWorldAction(this),
                SeparatorLayerAction.INSTANCE,
                new RenameLayerAction(null, this),
        };
    }

    @Override
    public String getToolTipText() {
        return getPicLayerName();
    }

    public List<ImageEntry> getImages() {
        List<ImageEntry> list = new ArrayList<>();
        list.add(new ImageEntry(imageFile));
        return list;
    }

    public PictureTransform getTransformer() {
        return transformer;
    }

    public GeoLine getRefLine1To2() {
        return refLine1To2;
    }

    public GeoLine getRefLine2To3() {
        return refLine2To3;
    }

    public void setDrawOriginPoints(boolean value) {
        drawOriginMarkers = value;
    }

    public void setDrawReferencePoints(boolean value) {
        drawRefMarkers = value;
    }

    public void setDrawOrigin1To2Line(boolean value) {
        drawOrigin1To2Line = value;
    }

    public void setDrawOrigin2To3Line(boolean value) {
        drawOrigin2To3Line = value;
    }

    public void setDrawRef1To2Line(Point2D p1, Point2D p2) {
        drawRef1To2Line = true;
        refLine1To2 = new GeoLine(p1, p2);
    }

    public void unsetDrawRef1ToRef2Line() {
        drawRef1To2Line = false;
    }

    public void setDrawRef2To3Line(Point2D p1, Point2D p2) {
        drawRef2To3Line = true;
        refLine2To3 = new GeoLine(p1, p2);
    }

    public void unsetDrawRef2ToRef3Line() {
        drawRef2To3Line = false;
    }

    @Override
    public void paint(Graphics2D g2, MapView mv, Bounds bounds) {
        if (image != null) {
            // Position image at the right graphical place
            EastNorth center = mv.getCenter();
            EastNorth leftop = mv.getEastNorth(0, 0);
            // Number of pixels for one unit in east north space.
            // This is the same in x- and y- direction.
            double pixel_per_en = (mv.getWidth() / 2.0) / (center.east() - leftop.east());

            // This is now the offset in screen pixels
            EastNorth imagePosition = transformer.getImagePosition();
            double pic_offset_x = ((imagePosition.east() - leftop.east()) * pixel_per_en);
            double pic_offset_y = ((leftop.north() - imagePosition.north()) * pixel_per_en);

            Graphics2D g = (Graphics2D) g2.create();
            // Move
            g.translate(pic_offset_x, pic_offset_y);

            // Scale
            double scalex = initialImageScale * pixel_per_en / getMetersPerEasting(imagePosition) / 100;
            double scaley = initialImageScale * pixel_per_en / getMetersPerNorthing(imagePosition) / 100;
            g.scale(scalex, scaley);

            g.transform(transformer.getTransform());

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            // Draw picture
            int width = image.getWidth(null);
            int height = image.getHeight(null);
            try {
                g.drawImage(image, -width / 2, -height / 2, null);
            } catch (RuntimeException e) {
                Logging.error(e);
            }

            // Draw additional rectangle for the active pic layer
            if (mv.getLayerManager().getActiveLayer() == this) {
                g.setColor(new Color(0xFF0000));
                g.drawRect(
                        -width / 2,
                        -height / 2,
                        width,
                        height
                );
            }

            // AutoCalibration - Graphics setup for marker
            Graphics2D gPoints = (Graphics2D) g2.create();
            gPoints.translate(pic_offset_x, pic_offset_y);
            gPoints.setColor(Color.RED); // red color for points output
            AffineTransform tr = AffineTransform.getScaleInstance(scalex, scaley);
            tr.concatenate(transformer.getTransform());

            // AutoCalibration - Draw markers and lines
            if (drawOriginMarkers) {
                // draw origin markers
                List<Point2D> points = transformer.getOriginPoints();

                for (int i = 0; i < points.size(); i++) {
                    Point2D trP = tr.transform(transformer.getOriginPoints().get(i), null);
                    drawMarkerImage(gPoints, pinTiledImage, trP, i);
                }

                if (drawOrigin1To2Line && points.size() > 2) {
                    drawLine(g, points.get(0), points.get(1));
                }
                if (drawOrigin2To3Line && points.size() > 3) {
                    drawLine(g, points.get(1), points.get(2));
                }
            }
            if (drawRefMarkers) {
                // draw reference markers
                for (int i = 0; i < transformer.getLatLonRefPoints().size(); i++) {
                    Point2D trPLocal = transformPointToPicLayerScale(transformer.getLatLonRefPoints().get(i));
                    Point2D trP = tr.transform(trPLocal, null);
                    drawMarkerImage(gPoints, pinTiledImageOrange, trP, i);
                }
                if (drawRef1To2Line && refLine1To2 != null) {
                    // draw line between reference point1 and point2
                    Point2D trP1Local = transformPointToPicLayerScale(refLine1To2.getStartPoint());
                    Point2D p1 = tr.transform(trP1Local, null);
                    Point2D trP2Local = transformPointToPicLayerScale(refLine1To2.getEndPoint());
                    Point2D p2 = tr.transform(trP2Local, null);
                    drawLine(gPoints, p1, p2);
                    drawMarkerImage(gPoints, pinTiledImageOrange, p2, 1);
                }
                if (drawRef2To3Line && refLine2To3 != null) {
                    // draw line between reference point2 and point3
                    Point2D trP1Local = transformPointToPicLayerScale(refLine2To3.getStartPoint());
                    Point2D p1 = tr.transform(trP1Local, null);
                    Point2D trP2Local = transformPointToPicLayerScale(refLine2To3.getEndPoint());
                    Point2D p2 = tr.transform(trP2Local, null);
                    drawLine(gPoints, p1, p2);
                    drawMarkerImage(gPoints, pinTiledImageOrange, p2, 2);
                }
            }
        } else {
            Logging.error("PicLayerAbstract::paint - general drawing error (image is null or Graphics not 2D");
        }
    }

    /**
     * Draw marker image
     *
     * @param g              {@link Graphics2D}
     * @param image          to draw on the map
     * @param markerPosition ,centered and transformed (by current {@link AffineTransform})
     * @param markerNumber   on image
     */
    private void drawMarkerImage(Graphics2D g, Image image, Point2D markerPosition, int markerNumber) {
        if (g == null) {
            return;
        }
        if (image == null) {
            return;
        }
        if (markerPosition == null) {
            return;
        }
        int x = (int) markerPosition.getX();
        int y = (int) markerPosition.getY();
        int dstx = x - pinAnchorX;
        int dsty = y - pinAnchorY;
        g.drawImage(image, dstx, dsty, dstx + pinWidth, dsty + pinHeight,
                pinTileOffsetX[markerNumber], pinTileOffsetY[markerNumber], pinTileOffsetX[markerNumber] + pinWidth, pinTileOffsetY[markerNumber] + pinHeight, null);
    }

    /**
     * Draws a green line from param p1 to param p2
     *
     * @param g  {@link Graphics2D}
     * @param p1 start point
     * @param p2 end point
     */
    private void drawLine(Graphics2D g, Point2D p1, Point2D p2) {
        if (g == null) {
            return;
        }
        if (p1 == null) {
            return;
        }
        if (p2 == null) {
            return;
        }
        g.setColor(Color.green);
        g.setStroke(new BasicStroke(5));
        g.drawLine((int) p1.getX(), (int) p1.getY(), (int) p2.getX(), (int) p2.getY());
    }

    /**
     * Returns the distance in meter, that corresponds to one unit in east north space.
     * For normal projections, it is about 1 (but usually changing with latitude).
     * For EPSG:4326, it is the distance from one meridian of full degree to the next (a couple of kilometers).
     *
     * @param en east/north
     * @return the distance in meter, that corresponds to one unit in east north space
     */
    protected double getMetersPerEasting(EastNorth en) {
        /* Natural scale in east/north units per pixel.
         * This means, the projection should be able to handle
         * a shift of that size in east north space without
         * going out of bounds.
         *
         * Also, this should get us somewhere in the range of meters,
         * so we get the result at the point 'en' and not some average.
         */
        double naturalScale = projection.getDefaultZoomInPPD();
        naturalScale *= 0.01; // make a little smaller

        LatLon ll1 = projection.eastNorth2latlon(
                new EastNorth(en.east() - naturalScale, en.north()));
        LatLon ll2 = projection.eastNorth2latlon(
                new EastNorth(en.east() + naturalScale, en.north()));

        return ll1.greatCircleDistance(ll2) / naturalScale / 2;
    }

    /* see getMetersPerEasting */
    private double getMetersPerNorthing(EastNorth en) {
        double naturalScale = projection.getDefaultZoomInPPD();
        naturalScale *= 0.01;

        LatLon ll1 = projection.eastNorth2latlon(
                new EastNorth(en.east(), en.north() - naturalScale));
        LatLon ll2 = projection.eastNorth2latlon(
                new EastNorth(en.east(), en.north() + naturalScale));

        return ll1.greatCircleDistance(ll2) / naturalScale / 2;
    }

    @Override
    /**
     * Computes the (rough) bounding box.
     * We ignore the rotation, the resulting bounding box contains any possible
     * rotation.
     */
    public void visitBoundingBox(BoundingXYVisitor arg0) {
        if (image == null)
            return;
        String projcode = projection.toCode();

        // TODO: bounding box only supported when coordinates are in meters
        // The reason for that is that this .cal think makes us a hard time.
        // The position is stored as a raw data (can be either in degrees or
        // in meters, depending on the projection used at creation), but the
        // initial scale is in m/100pix
        // So for now, we support the bounding box only when everything is in meters
        if (projcode.equals("EPSG:4326"))
            return;

        EastNorth center = transformer.getImagePosition();
        double w = image.getWidth(null);
        double h = image.getHeight(null);
        double diag_pix = Math.sqrt(w * w + h * h);

        // initialImageScale is a the scale (unit: m/100pix) at creation time
        double diag_m = (diag_pix / 100) * initialImageScale;

        AffineTransform trans = transformer.getTransform();
        double factor = Math.max(trans.getScaleX(), trans.getScaleY());

        double offset = factor * diag_m / 2.0;

        EastNorth topleft = center.add(-offset, -offset);
        EastNorth bottomright = center.add(offset, offset);
        arg0.visit(topleft);
        arg0.visit(bottomright);
    }

    /**
     * Saves the calibration data into properties structure
     *
     * @param props Properties to save to
     */
    public void saveCalibration(Properties props) {
        // Save
        double[] matrix = new double[6];
        transformer.getTransform().getMatrix(matrix);

        props.put(MATRIXm00, Double.toString(matrix[0]));
        props.put(MATRIXm01, Double.toString(matrix[1]));
        props.put(MATRIXm10, Double.toString(matrix[2]));
        props.put(MATRIXm11, Double.toString(matrix[3]));
        props.put(MATRIXm02, Double.toString(matrix[4]));
        props.put(MATRIXm12, Double.toString(matrix[5]));
        props.put(POSITION_X, Double.toString(transformer.getImagePosition().getX()));
        props.put(POSITION_Y, Double.toString(transformer.getImagePosition().getY()));
        props.put(INITIAL_SCALE, Double.toString(initialImageScale));

        transformer.resetModified();
    }

    /**
     * Loads calibration data from file
     *
     * @param is The input stream to read from
     * @throws IOException in case of error
     */
    public void loadCalibration(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);
        loadCalibration(props);
    }

    /**
     * Loads calibration data from properties structure
     *
     * @param props Properties to load from
     */
    public void loadCalibration(Properties props) {
        // Load

        AffineTransform transform;

        double pos_x = Double.parseDouble(props.getProperty(POSITION_X, "0"));
        double pos_y = Double.parseDouble(props.getProperty(POSITION_Y, "0"));

        EastNorth imagePosition = new EastNorth(pos_x, pos_y);
        transformer.setImagePosition(imagePosition);

        initialImageScale = Double.parseDouble(props.getProperty(INITIAL_SCALE, "1")); //in_scale
        if (props.containsKey(SCALEX)) { // old format
            //double in_pos_x = Double.valueOf(props.getProperty(INITIAL_POS_X, "0"));
            //double in_pos_y = Double.valueOf(props.getProperty(INITIAL_POS_Y, "0"));
            double angle = Double.parseDouble(props.getProperty(ANGLE, "0"));
            double scale_x = Double.valueOf(props.getProperty(SCALEX, "1"));
            double scale_y = Double.valueOf(props.getProperty(SCALEY, "1"));
            double shear_x = Double.valueOf(props.getProperty(SHEARX, "0"));
            double shear_y = Double.valueOf(props.getProperty(SHEARY, "0"));

            // transform to matrix from these values - need testing
            transform = AffineTransform.getRotateInstance(angle / 180 * Math.PI);
            transform.scale(scale_x, scale_y);
            transform.shear(shear_x, shear_y);
        } else {
            // initialize matrix
            double[] matrix = new double[6];
            matrix[0] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm00, "1"));
            matrix[1] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm01, "0"));
            matrix[2] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm10, "0"));
            matrix[3] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm11, "1"));
            matrix[4] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm02, "0"));
            matrix[5] = JosmDecimalFormatSymbolsProvider.parseDouble(props.getProperty(MATRIXm12, "0"));

            transform = new AffineTransform(matrix);
        }
        transformer.resetCalibration();
        transformer.getTransform().concatenate(transform);

        // Refresh
        invalidate();
    }

    public void loadWorldFile(InputStream is) throws IOException {

        try (
                Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(reader)
        ) {
            double[] e = new double[6];
            for (int i = 0; i < 6; ++i) {
                String line = br.readLine();
                if (line == null) {
                    throw new IOException("Unable to read line " + (i + 1));
                }
                e[i] = JosmDecimalFormatSymbolsProvider.parseDouble(line);
            }
            double sx = e[0], ry = e[1], rx = e[2], sy = e[3], dx = e[4], dy = e[5];
            int w = image.getWidth(null);
            int h = image.getHeight(null);
            EastNorth imagePosition = new EastNorth(
                    dx + w / 2 * sx + h / 2 * rx,
                    dy + w / 2 * ry + h / 2 * sy
            );
            double scalex = 100 * sx * getMetersPerEasting(imagePosition);
            double scaley = -100 * sy * getMetersPerNorthing(imagePosition);
            double shearx = rx / sx;
            double sheary = ry / sy;

            transformer.setImagePosition(imagePosition);
            transformer.resetCalibration();
            AffineTransform tr = transformer.getTransform();
            tr.scale(scalex, scaley);
            tr.shear(shearx, sheary);

            initialImageScale = 1;
            invalidate();
        }
    }

    public void saveWorldFile(double[] values) {
        double[] matrix = new double[6];
        transformer.getTransform().getMatrix(matrix);
        double a00 = matrix[0], a01 = matrix[2], a02 = matrix[4];
        double a10 = matrix[1], a11 = matrix[3], a12 = matrix[5];
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        EastNorth imagePosition = transformer.getImagePosition();
        // piclayer calibration stores 9 parameters
        // worldfile has 6 parameters
        // only 6 parameters needed, so write it in a way that
        // eliminates the 3 redundant parameters
        double qx = initialImageScale / 100 / getMetersPerEasting(imagePosition);
        double qy = -initialImageScale / 100 / getMetersPerNorthing(imagePosition);
        double sx = qx * a00;
        double sy = qy * a11;
        double rx = qx * a01;
        double ry = qy * a10;
        double dx = imagePosition.getX() + qx * a02 - sx * w / 2 - rx * h / 2;
        double dy = imagePosition.getY() + qy * a12 - ry * w / 2 - sy * h / 2;
        values[0] = sx;
        values[1] = ry;
        values[2] = rx;
        values[3] = sy;
        values[4] = dx;
        values[5] = dy;
    }

    public Point2D transformPoint(Point2D p) throws NoninvertibleTransformException {
        // Position image at the right graphical place

        EastNorth center = MainApplication.getMap().mapView.getCenter();
        EastNorth leftop = MainApplication.getMap().mapView.getEastNorth(0, 0);
        // Number of pixels for one unit in east north space.
        // This is the same in x- and y- direction.
        double pixel_per_en = (MainApplication.getMap().mapView.getWidth() / 2.0) / (center.east() - leftop.east());

        EastNorth imageCenter = transformer.getImagePosition();
        //     This is now the offset in screen pixels
        double pic_offset_x = ((imageCenter.east() - leftop.east()) * pixel_per_en);
        double pic_offset_y = ((leftop.north() - imageCenter.north()) * pixel_per_en); // something bad...

        AffineTransform pointTrans = AffineTransform.getTranslateInstance(pic_offset_x, pic_offset_y);

        double scalex = initialImageScale * pixel_per_en / getMetersPerEasting(imageCenter) / 100;
        double scaley = initialImageScale * pixel_per_en / getMetersPerNorthing(imageCenter) / 100;

        pointTrans.scale(scalex, scaley); // ok here

        pointTrans.concatenate(transformer.getTransform());

        return pointTrans.inverseTransform(p, null);
    }

    /**
     * Method to transforms {@code Point2D} to {@link PicLayerAbstract} scale.
     *
     * @param point to transform in LatLon
     * @return transformed point in {@link PicLayerAbstract} scale
     */
    private Point2D transformPointToPicLayerScale(Point2D point) {
        Point2D translatedPoint = null;
        LatLon ll;                // LatLon object from raw Point2D
        MapViewState.MapViewPoint en;        // MapViewPoint object from LatLon(ll) scaled in EastNorth(en)

        // put raw Point2D endPos into LatLon and transform LatLon into MapViewPoint (EastNorth)
        ll = new LatLon(point.getY(), point.getX());
        en = MainApplication.getMap().mapView.getState().getPointFor(ll);

        // transform EastNorth into current layer scale
        try {
            translatedPoint = transformPoint(new Point2D.Double(en.getInViewX(), en.getInViewY()));
        } catch (NoninvertibleTransformException e) {
            Logging.error(e);
        }

        return translatedPoint;
    }

    /**
     * Moves the picture. Scaled in EastNorth...
     *
     * @param x The offset to add in east direction
     * @param y The offset to add in north direction
     */
    public void movePictureBy(double x, double y) {
        transformer.setImagePosition(transformer.getImagePosition().add(x, y));
    }

    public void rotatePictureBy(double angle) {
        try {
            MapView mapView = MainApplication.getMap().mapView;
            Point2D trans = transformPoint(new Point(mapView.getWidth() / 2, mapView.getHeight() / 2));
            transformer.concatenateTransformPoint(AffineTransform.getRotateInstance(angle), trans);
        } catch (NoninvertibleTransformException e) {
            Logging.error(e);
        }
    }

    public void scalePictureBy(double scalex, double scaley) {
        try {
            MapView mapView = MainApplication.getMap().mapView;
            Point2D trans = transformPoint(new Point(mapView.getWidth() / 2, mapView.getHeight() / 2));
            transformer.concatenateTransformPoint(AffineTransform.getScaleInstance(scalex, scaley), trans);
        } catch (NoninvertibleTransformException e) {
            Logging.error(e);
        }
    }

    public void shearPictureBy(double shx, double shy) {
        try {
            MapView mapView = MainApplication.getMap().mapView;
            Point2D trans = transformPoint(new Point(mapView.getWidth() / 2, mapView.getHeight() / 2));
            transformer.concatenateTransformPoint(AffineTransform.getShearInstance(shx, shy), trans);
        } catch (NoninvertibleTransformException e) {
            Logging.error(e);
        }
    }

    public void resetCalibration() {
        transformer.resetCalibration();
    }

    // get image coordinates by mouse coords
    public Point2D findSelectedPoint(Point point) {
        if (image == null)
            return null;

        Point2D selected = null;
        try {
            Point2D pressed = transformPoint(point);
            double mindist = 10;
            for (Point2D p : transformer.getOriginPoints()) {
                if (p.distance(pressed) < mindist) { // if user clicked to select some of origin point
                    selected = p;
                    mindist = p.distance(pressed);
                }
            }
            return selected;
        } catch (NoninvertibleTransformException e) {
            Logging.error(e);
        }
        return null;
    }

    public void resetDrawReferencePoints() {
        drawRefMarkers = false;
        transformer.getLatLonRefPoints().clear();
    }

    private void resetDrawLines() {
        drawOrigin1To2Line = false;
        drawOrigin2To3Line = false;
        drawRef1To2Line = false;
        drawRef2To3Line = false;
        refLine1To2 = null;
        refLine2To3 = null;
    }

    public void resetMarkersAndUsabilityValues() {
        resetDrawReferencePoints();
        resetDrawLines();
        drawOriginMarkers = true;
    }
}

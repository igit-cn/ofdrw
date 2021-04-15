package org.ofdrw.converter;

import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.jbig2.JBIG2ImageReader;
import org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi;
import org.apache.pdfbox.jbig2.io.DefaultInputStreamFactory;
import org.apache.pdfbox.jbig2.util.log.Logger;
import org.apache.pdfbox.jbig2.util.log.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.ofdrw.converter.image.ImageMedia;
import org.ofdrw.converter.point.PathPoint;
import org.ofdrw.converter.point.TextCodePoint;
import org.ofdrw.converter.utils.CommonUtil;
import org.ofdrw.converter.utils.PointUtil;
import org.ofdrw.core.annotation.pageannot.Annot;
import org.ofdrw.core.basicStructure.pageObj.Page;
import org.ofdrw.core.basicStructure.pageObj.layer.CT_Layer;
import org.ofdrw.core.basicStructure.pageObj.layer.PageBlockType;
import org.ofdrw.core.basicStructure.pageObj.layer.block.*;
import org.ofdrw.core.basicType.ST_Array;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.basicType.ST_Pos;
import org.ofdrw.core.basicType.ST_RefID;
import org.ofdrw.core.compositeObj.CT_VectorG;
import org.ofdrw.core.graph.pathObj.FillColor;
import org.ofdrw.core.graph.pathObj.StrokeColor;
import org.ofdrw.core.pageDescription.color.color.CT_AxialShd;
import org.ofdrw.core.pageDescription.color.color.CT_Color;
import org.ofdrw.core.pageDescription.drawParam.CT_DrawParam;
import org.ofdrw.core.signatures.appearance.StampAnnot;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.reader.DLOFDReader;
import org.ofdrw.reader.ResourceManage;
import org.ofdrw.reader.model.AnnotionVo;
import org.ofdrw.reader.model.OfdPageVo;
import org.ofdrw.reader.model.StampAnnotVo;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.ofdrw.converter.utils.CommonUtil.*;


public class PdfboxMaker {

    private final Logger logger = LoggerFactory.getLogger(PdfboxMaker.class);

    private final DLOFDReader ofdReader;
    private final PDDocument pdf;
    private final ResourceManage resMgt;
    private final PDType0Font DEF_FONT;


    public PdfboxMaker(DLOFDReader ofdReader, PDDocument pdf) throws IOException {
        this.ofdReader = ofdReader;
        this.pdf = pdf;
        this.resMgt = ofdReader.getResMgt();
        this.DEF_FONT = PDType0Font.load(pdf, this.getClass().getClassLoader().getResourceAsStream("fonts/simsun.ttf"));
    }

    public PDPage makePage(OfdPageVo pageVo) throws IOException {
        Page contentPage = pageVo.getContentPage();
        ST_Box pageBox = getPageBox(contentPage.getArea(),
                ofdReader.getOFDDocumentVo().getPageWidth(),
                ofdReader.getOFDDocumentVo().getPageHeight());
        double pageWidthPixel = converterDpi(pageBox.getWidth());
        double pageHeightPixel = converterDpi(pageBox.getHeight());

        PDRectangle pageSize = new PDRectangle((float) pageWidthPixel, (float) pageHeightPixel);
        PDPage pdfPage = new PDPage(pageSize);
        pdf.addPage(pdfPage);

        try (PDPageContentStream contentStream = new PDPageContentStream(pdf, pdfPage);) {
            List<CT_Layer> layerList = new ArrayList<>();
            if (pageVo.getTemplatePage() != null
                    && pageVo.getTemplatePage().getContent() != null) {
                layerList.addAll(pageVo.getTemplatePage().getContent().getLayers());
            }
            if (pageVo.getContentPage() != null
                    && pageVo.getContentPage().getContent() != null) {
                layerList.addAll(pageVo.getContentPage().getContent().getLayers());
            }
            // 绘制 模板层 和 页面内容层
            writeLayer(resMgt, contentStream, layerList, pageBox, null);

            // 页面的对象ID
            final String pageID = contentPage.getObjID().toString();
            // make seal content
            for (StampAnnotVo stampAnnotVo : ofdReader.getOFDDocumentVo().getStampAnnotVos()) {
                List<StampAnnot> stampAnnots = stampAnnotVo.getStampAnnots();
                for (StampAnnot stampAnnot : stampAnnots) {
                    if (!stampAnnot.getPageRef().toString().equals(pageID)) {
                        // 不是同一个页面忽略
                        continue;
                    }
                    ST_Box sealBox = stampAnnot.getBoundary();
                    ST_Box clipBox = stampAnnot.getClip();
                    if (stampAnnotVo.getType().equalsIgnoreCase("ofd")) {
                        try (DLOFDReader sealOfdReader = new DLOFDReader(new ByteArrayInputStream(stampAnnotVo.getImgByte()));) {
                            ResourceManage sealResMgt = sealOfdReader.getResMgt();
                            for (OfdPageVo ofdPageVo : sealOfdReader.getOFDPageVO()) {
                                ArrayList<CT_Layer> sealLayer = new ArrayList<>();
                                // 模板页
                                if (ofdPageVo.getTemplatePage() != null
                                        && ofdPageVo.getTemplatePage().getContent() != null) {
                                    sealLayer.addAll(ofdPageVo.getTemplatePage().getContent().getLayers());
                                }
                                if (ofdPageVo.getContentPage() != null
                                        && ofdPageVo.getContentPage().getContent() != null) {
                                    sealLayer.addAll(ofdPageVo.getContentPage().getContent().getLayers());
                                }
                                // 绘制页面内容
                                writeLayer(sealResMgt, contentStream, sealLayer, pageBox, sealBox);
                                // 绘制注释
                                writeAnnoAppearance(sealResMgt,
                                        ofdPageVo,
                                        sealOfdReader.getOFDDocumentVo().getAnnotaions(),
                                        contentStream, pageBox);
                            }
                        }
                    } else {
                        // 绘制图片印章内容
                        writeSealImage(contentStream, pageBox, stampAnnotVo.getImgByte(), sealBox, clipBox);
                    }
                }
            }
            // 绘制注解
            writeAnnoAppearance(this.resMgt, pageVo, ofdReader.getOFDDocumentVo().getAnnotaions(), contentStream, pageBox);
        }
        return pdfPage;
    }


    private void writeLayer(ResourceManage resMgt,
                            PDPageContentStream contentStream,
                            List<CT_Layer> layerList,
                            ST_Box box,
                            ST_Box sealBox) throws IOException {
        for (CT_Layer layer : layerList) {
            List<PageBlockType> pageBlockTypeList = layer.getPageBlocks();
            writePageBlock(resMgt,
                    contentStream,
                    box,
                    sealBox,
                    pageBlockTypeList,
                    layer.getDrawParam(),
                    null, null, null, null);
        }
    }

    /**
     * 绘制注释到页面
     *
     * @param resMgt        资源管理器
     * @param pageVo        页面对象
     * @param annotionVos   注解列表
     * @param contentStream PDF Content Stream
     * @param box           绘制区域
     * @throws IOException 绘制过程中IO操作异常
     */
    private void writeAnnoAppearance(ResourceManage resMgt,
                                     OfdPageVo pageVo,
                                     List<AnnotionVo> annotionVos,
                                     PDPageContentStream contentStream,
                                     ST_Box box) throws IOException {
        String pageId = pageVo.getContentPage().getObjID().toString();
        for (AnnotionVo annotionVo : annotionVos) {
            List<Annot> annotList = annotionVo.getAnnots();
            if (annotList == null) {
                continue;
            }
            if (!pageId.equalsIgnoreCase(annotionVo.getPageId())) {
                continue;
            }
            for (Annot annot : annotList) {
                List<PageBlockType> pageBlockTypeList = annot.getAppearance().getPageBlocks();
                //注释的boundary
                ST_Box annotBox = annot.getAppearance().getBoundary();
                writePageBlock(resMgt, contentStream, box, null, pageBlockTypeList, null, annotBox, null, null, null);
            }
        }
    }

    private void writePageBlock(ResourceManage resMgt,
                                PDPageContentStream contentStream,
                                ST_Box box, ST_Box sealBox,
                                List<PageBlockType> pageBlockTypeList,
                                ST_RefID drawparam,
                                ST_Box annotBox,
                                Integer compositeObjectAlpha,
                                ST_Box compositeObjectBoundary,
                                ST_Array compositeObjectCTM) throws IOException {
        // 初始化绘制属性
        PDColor defaultFillColor = new PDColor(new float[]{0.0f, 0.0f, 0.0f}, PDDeviceRGB.INSTANCE);
        PDColor defaultStrokeColor = new PDColor(new float[]{0.0f, 0.0f, 0.0f}, PDDeviceRGB.INSTANCE);
        float defaultLineWidth = 0.353f;
        // 递归的获取绘制参数
        CT_DrawParam ctDrawParam = null;
        if (drawparam != null) {
            ctDrawParam = resMgt.getDrawParamFinal(drawparam.toString());
        }
        if (ctDrawParam != null) {
            if (ctDrawParam.getLineWidth() != null) {
                defaultLineWidth = ctDrawParam.getLineWidth().floatValue();
            }
            if (ctDrawParam.getStrokeColor() != null) {
                defaultStrokeColor = convertPDColor(ctDrawParam.getStrokeColor().getValue());
            }
            if (ctDrawParam.getFillColor() != null) {
                defaultFillColor = convertPDColor(ctDrawParam.getFillColor().getValue());
            }
        }

        for (PageBlockType block : pageBlockTypeList) {
            if (block instanceof TextObject) {
                // text
                PDColor fillColor = defaultFillColor;
                TextObject textObject = (TextObject) block;
                int alpha = 255;
                if (textObject.getFillColor() != null) {
                    if (textObject.getFillColor().getValue() != null) {
                        fillColor = convertPDColor(textObject.getFillColor().getValue());
                    } else if (textObject.getFillColor().getColorByType() != null) {
                        // todo
                        CT_AxialShd ctAxialShd = textObject.getFillColor().getColorByType();
                        fillColor = convertPDColor(ctAxialShd.getSegments().get(0).getColor().getValue());
                    }
                    alpha = textObject.getFillColor().getAlpha();
                }
                writeText(contentStream, box, sealBox, textObject, fillColor, alpha);
            } else if (block instanceof ImageObject) {
                // image
                ImageObject imageObject = (ImageObject) block;
                resMgt.superDrawParam(imageObject); // 补充图元参数
                writeImage(resMgt, contentStream, box, imageObject, annotBox);
            } else if (block instanceof PathObject) {
                // path
                PathObject pathObject = (PathObject) block;
                resMgt.superDrawParam(pathObject); // 补充图元参数
                writePath(resMgt, contentStream, box, sealBox, annotBox, pathObject, defaultFillColor, defaultStrokeColor, defaultLineWidth, compositeObjectAlpha, compositeObjectBoundary, compositeObjectCTM);
            } else if (block instanceof CompositeObject) {
                CompositeObject compositeObject = (CompositeObject) block;
                // 获取引用的矢量资源
                CT_VectorG vectorG = resMgt.getCompositeGraphicUnit(compositeObject.getResourceID().toString());
                Integer currentCompositeObjectAlpha = compositeObject.getAlpha();
                ST_Box currentCompositeObjectBoundary = compositeObject.getBoundary();
                ST_Array currentCompositeObjectCTM = compositeObject.getCTM();
                writePageBlock(resMgt, contentStream, box, sealBox, vectorG.getContent().getPageBlocks(), drawparam, annotBox, currentCompositeObjectAlpha, currentCompositeObjectBoundary, currentCompositeObjectCTM);
                break;
            } else if (block instanceof CT_PageBlock) {
                writePageBlock(resMgt, contentStream, box, sealBox, ((CT_PageBlock) block).getPageBlocks(), drawparam, annotBox, compositeObjectAlpha, compositeObjectBoundary, compositeObjectCTM);
            }
        }
    }

    private void writePath(ResourceManage resMgt,
                           PDPageContentStream contentStream,
                           ST_Box box,
                           ST_Box sealBox,
                           ST_Box annotBox,
                           PathObject pathObject,
                           PDColor defaultFillColor,
                           PDColor defaultStrokeColor,
                           float defaultLineWidth,
                           Integer compositeObjectAlpha,
                           ST_Box compositeObjectBoundary,
                           ST_Array compositeObjectCTM) throws IOException {
        contentStream.saveGraphicsState();
        // 获取引用的绘制参数可能会null
        CT_DrawParam ctDrawParam = resMgt.superDrawParam(pathObject);
        if (ctDrawParam != null) {
            // 使用绘制参数补充缺省的颜色
            if (pathObject.getStrokeColor() == null
                    && ctDrawParam.getStrokeColor() != null) {
                pathObject.setStrokeColor(ctDrawParam.getStrokeColor());
            }
            if (pathObject.getFillColor() == null
                    && ctDrawParam.getFillColor() != null) {
                pathObject.setFillColor(ctDrawParam.getFillColor());
            }
        }

        // 设置描边颜色
        final StrokeColor strokeColor = pathObject.getStrokeColor();
        if (strokeColor != null) {
            if (strokeColor.getValue() != null) {
                contentStream.setStrokingColor(convertPDColor(strokeColor.getValue()));
            } else {
                setShadingFill(contentStream, strokeColor, false);
            }
        } else {
            contentStream.setStrokingColor(defaultStrokeColor);
        }
        float lineWidth = pathObject.getLineWidth() != null ? pathObject.getLineWidth().floatValue() : defaultLineWidth;
        if (pathObject.getCTM() != null && pathObject.getLineWidth() != null) {
            Double[] ctm = pathObject.getCTM().toDouble();
            double a = ctm[0].doubleValue();
            double b = ctm[1].doubleValue();
            double c = ctm[2].doubleValue();
            double d = ctm[3].doubleValue();
            double e = ctm[4].doubleValue();
            double f = ctm[5].doubleValue();
            double sx = Math.signum(a) * Math.sqrt(a * a + c * c);
            double sy = Math.signum(d) * Math.sqrt(b * b + d * d);
            lineWidth = (float) (lineWidth * sx);
        }
        if (pathObject.getStroke()) {
            if (compositeObjectAlpha != null) {
                PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
                graphicsState.setStrokingAlphaConstant(compositeObjectAlpha * 1.0f / 255);
                contentStream.setGraphicsStateParameters(graphicsState);
            }
            if (pathObject.getDashPattern() != null) {
                float unitsOn = (float) converterDpi(pathObject.getDashPattern().toDouble()[0].floatValue());
                float unitsOff = (float) converterDpi(pathObject.getDashPattern().toDouble()[1].floatValue());
                float phase = (float) converterDpi(pathObject.getDashOffset().floatValue());
                contentStream.setLineDashPattern(new float[]{unitsOn, unitsOff}, phase);
            }
            contentStream.setLineJoinStyle(pathObject.getJoin().ordinal());
            contentStream.setLineCapStyle(pathObject.getCap().ordinal());
            contentStream.setMiterLimit(pathObject.getMiterLimit().floatValue());
            path(contentStream, box, sealBox, annotBox, pathObject, compositeObjectBoundary, compositeObjectCTM);
            contentStream.setLineWidth((float) converterDpi(lineWidth));
            contentStream.stroke();
            contentStream.restoreGraphicsState();
        }
        if (pathObject.getFill()) {
            contentStream.saveGraphicsState();
            if (compositeObjectAlpha != null) {
                PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
                graphicsState.setNonStrokingAlphaConstant(compositeObjectAlpha * 1.0f / 255);
                contentStream.setGraphicsStateParameters(graphicsState);
            }
            FillColor fillColor = (FillColor) pathObject.getFillColor();
            if (fillColor != null) {
                if (fillColor.getValue() != null) {
                    contentStream.setNonStrokingColor(convertPDColor(fillColor.getValue()));
                } else {
                    // todo
                    setShadingFill(contentStream, fillColor, true);
                }
            } else {
                contentStream.setNonStrokingColor(defaultFillColor);
            }
            path(contentStream, box, sealBox, annotBox, pathObject, compositeObjectBoundary, compositeObjectCTM);
            contentStream.fill();
            contentStream.restoreGraphicsState();
        }
    }

    private void setShadingFill(PDPageContentStream contentStream, CT_Color ctColor, boolean isFill) throws IOException {
        CT_AxialShd ctAxialShd = ctColor.getColorByType();
        if (ctAxialShd == null) {
            return;
        }
        ST_Array start = ctAxialShd.getSegments().get(0).getColor().getValue();
        ST_Array end = ctAxialShd.getSegments().get(ctAxialShd.getSegments().size() - 1).getColor().getValue();
        ST_Pos startPos = ctAxialShd.getStartPoint();
        ST_Pos endPos = ctAxialShd.getEndPoint();
        if (isFill) {
            contentStream.setNonStrokingColor(convertPDColor(end));
        } else {
            contentStream.setStrokingColor(convertPDColor(end));
        }
//                    COSDictionary fdict = new COSDictionary();
//                    fdict.setInt(COSName.FUNCTION_TYPE, 2); // still not understaning that...
//                    COSArray domain = new COSArray();
//                    domain.add(COSInteger.get(0));
//                    domain.add(COSInteger.get(1));
//                    COSArray c0 = new COSArray();
//                    Double[] first = ctAxialShd.getSegments().get(0).getColor().getValue().toDouble();
//                    Double[] end = ctAxialShd.getSegments().get(ctAxialShd.getSegments().size() - 1).getColor().getValue().toDouble();
//                    c0.add(COSFloat.get(String.format("%.2f", first[0] * 1.0 / 255)));
//                    c0.add(COSFloat.get(String.format("%.2f", first[1] * 1.0 / 255)));
//                    c0.add(COSFloat.get(String.format("%.2f", first[2] * 1.0 / 255)));
//                    COSArray c2 = new COSArray();
//                    c2.add(COSFloat.get(String.format("%.2f", end[0] * 1.0 / 255)));
//                    c2.add(COSFloat.get(String.format("%.2f", end[1] * 1.0 / 255)));
//                    c2.add(COSFloat.get(String.format("%.2f", end[2] * 1.0 / 255)));
//                    fdict.setItem(COSName.DOMAIN, domain);
//                    fdict.setItem(COSName.C0, c0);
//                    fdict.setItem(COSName.C1, c2);
//                    fdict.setInt(COSName.N, 1);
//
//                    PDFunctionType2 func = new PDFunctionType2(fdict);
//
//                    PDShadingType2 axialShading = new PDShadingType2(new COSDictionary());
//                    axialShading.setColorSpace(PDDeviceRGB.INSTANCE);
//                    axialShading.setShadingType(PDShading.SHADING_TYPE2);
//                    COSArray coords1 = new COSArray();
//                    coords1.add(COSFloat.get(String.valueOf(ctAxialShd.getStartPoint().getX())));
//                    coords1.add(COSFloat.get(String.valueOf(ctAxialShd.getStartPoint().getY())));
//                    coords1.add(COSFloat.get(String.valueOf(ctAxialShd.getEndPoint().getX())));
//                    coords1.add(COSFloat.get(String.valueOf(ctAxialShd.getEndPoint().getY())));
//                    axialShading.setCoords(coords1); // so this sets the bounds of my gradient
//                    axialShading.setFunction(func); // and this determines all the curves etc?
//                    contentStream.shadingFill(axialShading);
    }

    private void path(PDPageContentStream contentStream, ST_Box box, ST_Box sealBox, ST_Box annotBox, PathObject pathObject, ST_Box compositeObjectBoundary, ST_Array compositeObjectCTM) throws IOException {
        if (pathObject.getBoundary() == null) {
            return;
        }
        if (sealBox != null) {
            pathObject.setBoundary(pathObject.getBoundary().getTopLeftX() + sealBox.getTopLeftX(),
                    pathObject.getBoundary().getTopLeftY() + sealBox.getTopLeftY(),
                    pathObject.getBoundary().getWidth(),
                    pathObject.getBoundary().getHeight());
        }
        if (annotBox != null) {
            pathObject.setBoundary(pathObject.getBoundary().getTopLeftX() + annotBox.getTopLeftX(),
                    pathObject.getBoundary().getTopLeftY() + annotBox.getTopLeftY(),
                    pathObject.getBoundary().getWidth(),
                    pathObject.getBoundary().getHeight());
        }
        List<PathPoint> listPoint = PointUtil.calPdfPathPoint(box.getWidth(), box.getHeight(), pathObject.getBoundary(), PointUtil.convertPathAbbreviatedDatatoPoint(pathObject.getAbbreviatedData()), pathObject.getCTM() != null, pathObject.getCTM(), compositeObjectBoundary, compositeObjectCTM, true);
        for (int i = 0; i < listPoint.size(); i++) {
            if (listPoint.get(i).type.equals("M") || listPoint.get(i).type.equals("S")) {
                contentStream.moveTo(listPoint.get(i).x1, listPoint.get(i).y1);
            } else if (listPoint.get(i).type.equals("L")) {
                contentStream.lineTo(listPoint.get(i).x1, listPoint.get(i).y1);
            } else if (listPoint.get(i).type.equals("B")) {
                contentStream.curveTo(listPoint.get(i).x1, listPoint.get(i).y1,
                        listPoint.get(i).x2, listPoint.get(i).y2,
                        listPoint.get(i).x3, listPoint.get(i).y3);
            } else if (listPoint.get(i).type.equals("Q")) {
                contentStream.curveTo1(listPoint.get(i).x1, listPoint.get(i).y1,
                        listPoint.get(i).x2, listPoint.get(i).y2);
            } else if (listPoint.get(i).type.equals("C")) {
                contentStream.closePath();
            }
        }
    }

    private void writeImage(ResourceManage resMgt, PDPageContentStream contentStream, ST_Box box, ImageObject imageObject, ST_Box annotBox) throws IOException {
        // 读取图片
        BufferedImage bufferedImage = resMgt.getImage(imageObject.getResourceID().toString());
        if (bufferedImage == null) {
            return;
        }
        contentStream.saveGraphicsState();
        PDImageXObject pdfImageObject = LosslessFactory.createFromImage(pdf, bufferedImage);

        if (annotBox != null) {
            float x = annotBox.getTopLeftX().floatValue();
            float y = box.getHeight().floatValue() - (annotBox.getTopLeftY().floatValue() + annotBox.getHeight().floatValue());
            float width = annotBox.getWidth().floatValue();
            float height = annotBox.getHeight().floatValue();
            contentStream.drawImage(pdfImageObject, (float) converterDpi(x), (float) converterDpi(y), (float) converterDpi(width), (float) converterDpi(height));
        } else {
            org.apache.pdfbox.util.Matrix matrix = CommonUtil.toPFMatrix(CommonUtil.getImageMatrixFromOfd(imageObject, box));
            contentStream.drawImage(pdfImageObject, matrix);
        }
        contentStream.restoreGraphicsState();
    }

    /**
     * 读取图片文件
     *
     * @param isJb2 是否Jb2
     * @param image 图片输入流
     * @return 图片对象
     * @throws IOException 读取异常
     */
    public BufferedImage readImageFile(boolean isJb2, InputStream image) throws IOException {
        if (isJb2) {
            DefaultInputStreamFactory defaultInputStreamFactory = new DefaultInputStreamFactory();
            ImageInputStream imageInputStream = defaultInputStreamFactory.getInputStream(image);
            JBIG2ImageReader imageReader = new JBIG2ImageReader(new JBIG2ImageReaderSpi());
            imageReader.setInput(imageInputStream);
            return imageReader.read(0, imageReader.getDefaultReadParam());
        } else {
            return ImageIO.read(image);
        }
    }

    private void writeSealImage(PDPageContentStream contentStream, ST_Box box, byte[] image, ST_Box sealBox, ST_Box clipBox) throws IOException {
        if (image == null) {
            return;
        }
        contentStream.saveGraphicsState();
        PDImageXObject pdfImageObject = PDImageXObject.createFromByteArray(pdf, image, "");
        float x = sealBox.getTopLeftX().floatValue();
        float y = box.getHeight().floatValue() - (sealBox.getTopLeftY().floatValue() + sealBox.getHeight().floatValue());
        float width = sealBox.getWidth().floatValue();
        float height = sealBox.getHeight().floatValue();
        if (clipBox != null) {
            contentStream.addRect((float) converterDpi(x) + (float) converterDpi(clipBox.getTopLeftX()), (float) converterDpi(y) + (float) (converterDpi(height) - (converterDpi(clipBox.getTopLeftY()) + converterDpi(clipBox.getHeight()))), (float) converterDpi(clipBox.getWidth()), (float) converterDpi(clipBox.getHeight()));
            contentStream.closePath();
            contentStream.clip();
            contentStream.stroke();
        }
        contentStream.drawImage(pdfImageObject, (float) converterDpi(x), (float) converterDpi(y), (float) converterDpi(width), (float) converterDpi(height));
        contentStream.restoreGraphicsState();
    }

    private void writeText(PDPageContentStream contentStream, ST_Box box, ST_Box sealBox, TextObject textObject, PDColor fillColor, int alpha) throws IOException {
        float fontSize = textObject.getSize().floatValue();

        String fontAno = "";
        if (sealBox != null && textObject.getBoundary() != null) {
            fontAno = "s";
            textObject.setBoundary(textObject.getBoundary().getTopLeftX() + sealBox.getTopLeftX(),
                    textObject.getBoundary().getTopLeftY() + sealBox.getTopLeftY(),
                    textObject.getBoundary().getWidth(),
                    textObject.getBoundary().getHeight());
        }
        if (textObject.getCTM() != null) {
            Double[] ctm = textObject.getCTM().toDouble();
            double a = ctm[0];
            double b = ctm[1];
            double c = ctm[2];
            double d = ctm[3];
            double sx = a > 0 ? Math.signum(a) * Math.sqrt(a * a + c * c) : Math.sqrt(a * a + c * c);
            double sy = Math.signum(d) * Math.sqrt(b * b + d * d);
            double angel = Math.atan2(-b, d);
            if (!(angel == 0 && a != 0 && d == 1)) {
                fontSize = (float) (fontSize * sx);
            }
        }

        // 加载字体
        CT_Font ctFont = resMgt.getFont(textObject.getFont().toString());
        TrueTypeFont typeFont = FontLoader.getInstance().loadFont(ctFont);
        PDFont font;
        if (typeFont != null) {
            font = PDType0Font.load(pdf, typeFont, true);
        } else {
            font = DEF_FONT;
        }

        List<TextCodePoint> textCodePointList = PointUtil.calPdfTextCoordinate(box.getWidth(), box.getHeight(), textObject.getBoundary(), fontSize, textObject.getTextCodes(), textObject.getCTM() != null, textObject.getCTM(), true);
        double rx = 0, ry = 0;
        for (int i = 0; i < textCodePointList.size(); i++) {
            TextCodePoint textCodePoint = textCodePointList.get(i);
            if (i == 0) {
                rx = textCodePoint.x;
                ry = textCodePoint.y;
            }
            contentStream.saveGraphicsState();
            contentStream.beginText();
            contentStream.setNonStrokingColor(fillColor);
            contentStream.newLineAtOffset((float) (textCodePoint.getX()), (float) (textCodePoint.getY()));
            if (textObject.getCTM() != null) {
                Double[] ctm = textObject.getCTM().toDouble();
                double a = ctm[0];
                double b = ctm[1];
                double c = ctm[2];
                double d = ctm[3];
                AffineTransform transform = new AffineTransform();
                double angel = Math.atan2(-b, d);
                transform.rotate(angel, rx, ry);
                contentStream.concatenate2CTM(transform);
                if (angel == 0 && a != 0 && d == 1) {
                    textObject.setHScale(a);
                }
            }
            if (textObject.getHScale().floatValue() < 1) {
                AffineTransform transform = new AffineTransform();
                transform.setTransform(textObject.getHScale().floatValue(), 0, 0, 1, (1 - textObject.getHScale().floatValue()) * textCodePoint.getX(), 0);
                contentStream.concatenate2CTM(transform);
            }
            contentStream.setFont(font, (float) converterDpi(fontSize));
            try {
                contentStream.showText(textCodePoint.getText());
            } catch (Exception e) {

            }
            contentStream.endText();
            contentStream.restoreGraphicsState();
        }

    }
}

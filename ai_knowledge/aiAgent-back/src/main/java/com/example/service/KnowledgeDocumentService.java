package com.example.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.example.common.enums.ResultCodeEnum;
import com.example.entity.KnowledgeDocument;
import com.example.exception.CustomException;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.mapper.KnowledgeSegmentMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class KnowledgeDocumentService {

    private static final String FILE_PATH = System.getProperty("user.dir") + "/files/";

    @Resource
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    @Resource
    private KnowledgeSegmentMapper knowledgeSegmentMapper;
    @Resource
    private PgVectorService pgVectorService;

    public void add(KnowledgeDocument knowledgeDocument) {
        if (ObjectUtil.isEmpty(knowledgeDocument.getParseStatus())) {
            knowledgeDocument.setParseStatus("待解析");
        }
        if (ObjectUtil.isEmpty(knowledgeDocument.getVectorStatus())) {
            knowledgeDocument.setVectorStatus("未向量化");
        }
        String now = DateUtil.now();
        knowledgeDocument.setCreateTime(now);
        knowledgeDocument.setUpdateTime(now);
        knowledgeDocumentMapper.insert(knowledgeDocument);
    }

    @Transactional
    public void updateById(KnowledgeDocument knowledgeDocument) {
        KnowledgeDocument dbDocument = knowledgeDocumentMapper.selectById(knowledgeDocument.getId());
        if (ObjectUtil.isNotNull(dbDocument)
                && (isChanged(dbDocument.getFileName(), knowledgeDocument.getFileName())
                || isChanged(dbDocument.getFileUrl(), knowledgeDocument.getFileUrl()))) {
            knowledgeDocumentMapper.resetProcessById(knowledgeDocument.getId(), DateUtil.now());
            pgVectorService.deleteByDocumentId(knowledgeDocument.getId());
            knowledgeSegmentMapper.deleteByDocumentId(knowledgeDocument.getId());
        }
        knowledgeDocument.setUpdateTime(DateUtil.now());
        knowledgeDocumentMapper.updateById(knowledgeDocument);
    }

    @Transactional
    public void deleteById(Integer id) {
        pgVectorService.deleteByDocumentId(id);
        knowledgeSegmentMapper.deleteByDocumentId(id);
        knowledgeDocumentMapper.deleteById(id);
    }

    @Transactional
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    public List<KnowledgeDocument> selectAll(KnowledgeDocument knowledgeDocument) {
        return knowledgeDocumentMapper.selectAll(knowledgeDocument);
    }

    public PageInfo<KnowledgeDocument> selectPage(KnowledgeDocument knowledgeDocument, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<KnowledgeDocument> list = knowledgeDocumentMapper.selectAll(knowledgeDocument);
        return PageInfo.of(list);
    }

    public KnowledgeDocument parseById(Integer id) {
        KnowledgeDocument knowledgeDocument = knowledgeDocumentMapper.selectById(id);
        if (ObjectUtil.isNull(knowledgeDocument)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "文档不存在");
        }
        if (StrUtil.isEmpty(knowledgeDocument.getFileName())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "文档文件不存在");
        }
        try {
            String text = parseFile(knowledgeDocument);
            if (StrUtil.isBlank(text)) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "文档没有解析到有效文本");
            }
            knowledgeDocument.setParsedText(formatParsedText(text));
            knowledgeDocument.setParseStatus("已解析");
            knowledgeDocument.setParseTime(DateUtil.now());
            knowledgeDocument.setUpdateTime(DateUtil.now());
            knowledgeDocumentMapper.updateById(knowledgeDocument);
            return knowledgeDocument;
        } catch (CustomException e) {
            knowledgeDocument.setParseStatus("解析失败");
            knowledgeDocument.setUpdateTime(DateUtil.now());
            knowledgeDocumentMapper.updateById(knowledgeDocument);
            throw e;
        } catch (Exception e) {
            knowledgeDocument.setParseStatus("解析失败");
            knowledgeDocument.setUpdateTime(DateUtil.now());
            knowledgeDocumentMapper.updateById(knowledgeDocument);
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR.code, "文档解析失败");
        }
    }

    private String parseFile(KnowledgeDocument knowledgeDocument) throws Exception {
        File file = findLocalFile(knowledgeDocument);
        if (!file.exists()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "本地文件不存在");
        }
        String fileType = StrUtil.blankToDefault(knowledgeDocument.getFileType(), "").toLowerCase();
        if ("txt".equals(fileType) || "md".equals(fileType)) {
            return FileUtil.readString(file, CharsetUtil.CHARSET_UTF_8);
        }
        if ("docx".equals(fileType)) {
            return parseDocx(file);
        }
        if ("pdf".equals(fileType)) {
            return parsePdf(file);
        }
        throw new CustomException(ResultCodeEnum.PARAM_ERROR.code, "暂不支持该文件类型");
    }

    private boolean isChanged(String oldValue, String newValue) {
        return StrUtil.isNotBlank(newValue) && !StrUtil.equals(oldValue, newValue);
    }

    private File findLocalFile(KnowledgeDocument knowledgeDocument) {
        File file = new File(FILE_PATH + knowledgeDocument.getFileName());
        if (file.exists() || StrUtil.isBlank(knowledgeDocument.getFileUrl())) {
            return file;
        }
        String storedFileName = knowledgeDocument.getFileUrl();
        int index = storedFileName.lastIndexOf('/');
        if (index >= 0) {
            storedFileName = storedFileName.substring(index + 1);
        }
        storedFileName = URLDecoder.decode(storedFileName, StandardCharsets.UTF_8);
        return new File(FILE_PATH + storedFileName);
    }

    private String parseDocx(File file) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            return document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.joining("\n\n"));
        }
    }

    private String parsePdf(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String formatParsedText(String text) {
        return text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}

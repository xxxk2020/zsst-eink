package com.xxxk2020.zssteink;

import android.util.Base64;
import android.util.Base64OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A self-contained Doc v1. Only one question and one image buffer are held at a time. */
final class NativePortablePaper {
    private static final Pattern MARKER = Pattern.compile("\\[\\[img:([^\\]\\r\\n]+)\\]\\]");
    private static final Pattern HTML_IMAGE = Pattern.compile("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>");
    private static final Pattern NATIVE_IMAGE = Pattern.compile("@native:[a-f0-9]{64}");
    private final NativeRepository.Document document;
    private final LinkedHashMap<String,File> images = new LinkedHashMap<>();
    private final Map<String,String> references = new HashMap<>();
    private final JSONObject formulas = new JSONObject();

    NativePortablePaper(NativeRepository.Document document) { this.document = document; }

    void write(File target) throws Exception {
        try (FileOutputStream file = new FileOutputStream(target)) {
            OutputStream out = new FilterOutputStream(file) {
                private long bytes;
                @Override public void write(int value)throws IOException {check(1);this.out.write(value);}
                @Override public void write(byte[] data,int offset,int length)throws IOException {check(length);this.out.write(data,offset,length);}
                private void check(int length)throws IOException {bytes+=length;if(bytes>32L*1024*1024)throw new IOException("试卷超过32 MB，暂不能导出为单个 JSON");}
            };
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
            writer.write("{\"v\":1,\"id\":"); value(writer, document.item.id, "", 0);
            writer.write(",\"title\":"); value(writer, document.item.title, "", 0);
            writer.write(",\"kind\":"); value(writer, document.item.kind, "", 0);
            writer.write(",\"shape\":"); value(writer, document.item.shape, "", 0);
            writer.write(",\"subject\":"); value(writer, document.item.subject, "", 0);
            for(String field:Arrays.asList("course","teacher","unit","category","group","date")) {
                if(document.metadata.has(field)) {writer.write(","+JSONObject.quote(field)+":");value(writer,document.metadata.opt(field),field,0);}
            }
            if (document.metadata.has("questionModules")) {
                writer.write(",\"questionModules\":"); value(writer, document.metadata.opt("questionModules"), "", 0);
            }
            writer.write(",\"questions\":[");
            for (int i=0;i<document.questionCount();i++) {
                if(i>0)writer.write(',');JSONObject question=document.question(i);
                JSONArray options=question.optJSONArray("options");String answer=String.valueOf(question.opt("answer")).trim().toUpperCase(Locale.US);
                int choice=answer.matches("[0-7]")?Integer.parseInt(answer):answer.matches("[A-H]")?"ABCDEFGH".indexOf(answer):-1;
                if(options!=null&&choice>=0&&choice<options.length())question.put("answer",String.valueOf("ABCDEFGH".charAt(choice)));
                value(writer,question,"",0);
            }
            writer.write("],\"materials\":[");
            for (int i=0;i<document.explicitMaterialCount();i++) { if(i>0)writer.write(','); value(writer,document.material(i),"",0); }
            writer.write("],\"sections\":[");
            for (int i=0;i<document.sectionCount();i++) {if(i>0)writer.write(',');value(writer,document.section(i),"",0);}
            writer.write("],\"formulaImages\":"); writer.write(formulas.toString());
            writer.write(",\"figs\":{"); boolean first=true;
            for (Map.Entry<String,File> entry:images.entrySet()) {
                checkCancelled(); if(!first)writer.write(',');first=false;
                writer.write(JSONObject.quote(entry.getKey())); writer.write(":\"data:"+mime(entry.getValue())+";base64,");writer.flush();
                try(InputStream in=new FileInputStream(entry.getValue()); Base64OutputStream encoded=new Base64OutputStream(out,Base64.NO_WRAP|Base64.NO_CLOSE)) {
                    byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){checkCancelled();encoded.write(buffer,0,n);}
                }
                writer.write('"');
            }
            writer.write("}}");writer.flush();file.getFD().sync();
        } catch(Exception error) { target.delete();throw error; }
    }

    private void value(Writer writer,Object item,String field,int depth) throws Exception {
        checkCancelled(); if(depth>48)throw new IOException("试卷内容层级过深");
        if(item==null||item==JSONObject.NULL){writer.write("null");return;}
        if(item instanceof JSONObject){
            JSONObject object=(JSONObject)item; writer.write('{'); boolean first=true;
            List<String> keys=new ArrayList<>();for(Iterator<String> it=object.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);
            for(String key:keys){if(!first)writer.write(',');first=false;writer.write(JSONObject.quote(key));writer.write(':');value(writer,object.opt(key),key,depth+1);}writer.write('}');
        } else if(item instanceof JSONArray){
            JSONArray array=(JSONArray)item;writer.write('[');for(int i=0;i<array.length();i++){if(i>0)writer.write(',');value(writer,array.opt(i),field,depth+1);}writer.write(']');
        } else if(item instanceof String) writer.write(JSONObject.quote(text((String)item,field)));
        else if(item instanceof Number||item instanceof Boolean)writer.write(item.toString());
        else throw new IOException("试卷内容格式不支持");
    }

    private String text(String input,String field) throws Exception {
        Matcher html=HTML_IMAGE.matcher(input);StringBuffer buffer=new StringBuffer();
        while(html.find()) {String ref=html.group(1)!=null?html.group(1):html.group(2)!=null?html.group(2):html.group(3);html.appendReplacement(buffer,Matcher.quoteReplacement("[[img:"+image(ref)+"]]"));}
        html.appendTail(buffer);String value=buffer.toString();buffer=new StringBuffer();Matcher marker=MARKER.matcher(value);
        while(marker.find())marker.appendReplacement(buffer,Matcher.quoteReplacement("[[img:"+image(marker.group(1).trim())+"]]"));marker.appendTail(buffer);value=buffer.toString();
        if(images.containsKey(value))return value;
        if(value.startsWith("@native:")&&NATIVE_IMAGE.matcher(value).matches())return image(value);
        if(Arrays.asList("img","imgs","oimgs","mimgs","eimgs","aimgs","rimgs","images","src","data","url").contains(field)&&value.length()>0) {
            File local=document.image(value);if(local!=null)return image(value);
            if(!Arrays.asList("src","data","url").contains(field)||value.startsWith("data:image/")||value.matches("(?i).+\\.(png|jpg|jpeg|gif|bmp|webp)"))throw new IOException("试卷中有缺失的图片，请重新导入完整题库后再导出");
        }
        return value;
    }

    private String image(String reference) throws Exception {
        if(images.containsKey(reference))return reference;
        String known=references.get(reference);if(known!=null)return known;
        File file=document.image(reference);if(file==null)throw new IOException("试卷图片缺失，请重新导入完整题库后再导出");
        if(file.length()>24L*1024*1024)throw new IOException("单张试卷图片过大");
        String key="img_"+sha256(file);if(images.size()>=40000&&!images.containsKey(key))throw new IOException("试卷图片过多");
        references.put(reference,key);images.put(key,file);if(document.isFormula(reference))formulas.put(key,true);return key;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){checkCancelled();digest.update(buffer,0,n);}}
        StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(java.util.Locale.US,"%02x",b&255));return value.toString();
    }
    private static String mime(File file) throws Exception {
        byte[] head=new byte[16];int count;try(InputStream in=new FileInputStream(file)){count=in.read(head);}
        if(count>=8&&head[0]==(byte)137&&head[1]=='P'&&head[2]=='N'&&head[3]=='G')return "image/png";
        if(count>=3&&head[0]==(byte)255&&head[1]==(byte)216)return "image/jpeg";
        if(count>=6&&head[0]=='G'&&head[1]=='I'&&head[2]=='F')return "image/gif";
        if(count>=12&&head[0]=='R'&&head[8]=='W'&&head[9]=='E')return "image/webp";
        if(count>=2&&head[0]=='B'&&head[1]=='M')return "image/bmp";
        throw new IOException("试卷含不支持导出的图片格式");
    }
    private static void checkCancelled() throws IOException {if(Thread.currentThread().isInterrupted())throw new IOException("导出已取消");}
}


package com.xxxk2020.zssteink;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** Runs on the Activity's file queue. Original question documents are never rewritten. */
final class NativeTransfer {
    private NativeTransfer() { }
    static void importFile(Context context,Uri uri,String subject,NativeRepository repository,NativeProgress progress)throws Exception{
        try { importChecked(context,uri,subject,repository,progress); }
        catch(OutOfMemoryError tooLarge) { throw new IOException("内存不足，导入未完成；已保存记录仍保留，请分批导入",tooLarge); }
    }
    private static void importChecked(Context context,Uri uri,String subject,NativeRepository repository,NativeProgress progress)throws Exception{
        boolean zip;
        try(InputStream in=open(context,uri)){zip=in.read()==0x50&&in.read()==0x4b;}
        if(zip){ResourcePackManager.importPack(context,uri);repository.maintenance();return;}
        String app="",kind="";
        try(JsonReader reader=new JsonReader(new InputStreamReader(open(context,uri),"UTF-8"))){
            if(reader.peek()==JsonToken.BEGIN_OBJECT){
                reader.beginObject();
                while(reader.hasNext()){
                    String name=reader.nextName();
                    if("app".equals(name)&&reader.peek()==JsonToken.STRING)app=reader.nextString();
                    else if("kind".equals(name)&&reader.peek()==JsonToken.STRING)kind=reader.nextString();
                    else reader.skipValue();
                }
                reader.endObject();
            }else if(reader.peek()==JsonToken.BEGIN_ARRAY)reader.skipValue();
            else throw new IOException("请选择题库或进度 JSON 文件");
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("文件末尾存在多余内容");
        }
        if("zskg".equals(app)&&"progress".equals(kind)){
            JSONObject pack;
            try(InputStream in=open(context,uri)){pack=NativeProgressJson.read(in);}
            catch(OutOfMemoryError tooLarge){throw new IOException("内存不足，未导入这份进度；请分批导入，本机记录已保留",tooLarge);}
            progress.importProgress(pack);
        }else repository.importDocument(uri,subject);
    }
    static File writeProgress(Context context,JSONObject progress)throws Exception{
        File target=createExport(context);
        try(FileOutputStream out=new FileOutputStream(target)){out.write(progress.toString().getBytes("UTF-8"));out.getFD().sync();}
        catch(Exception e){target.delete();throw e;}return target;
    }
    static File copyForExport(Context context,File source)throws Exception{
        File target=createExport(context);
        try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){copy(in,out);out.getFD().sync();}
        catch(Exception e){target.delete();throw e;}return target;
    }
    private static File createExport(Context context)throws IOException{
        File dir=new File(context.getCacheDir(),"native-export");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备导出目录");
        return File.createTempFile("export-",".json",dir);
    }
    static InputStream open(Context context,Uri uri)throws IOException{
        InputStream in=context.getContentResolver().openInputStream(uri);if(in==null)throw new IOException("无法读取所选文件");return new BufferedInputStream(in);
    }
    static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("操作已取消");out.write(buffer,0,n);}
    }
}



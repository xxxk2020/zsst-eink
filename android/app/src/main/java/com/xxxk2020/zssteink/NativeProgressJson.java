package com.xxxk2020.zssteink;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/** A bounded streaming JSON tree: no second whole-file byte array/String for progress imports. */
final class NativeProgressJson {
    private static final int MAX_BYTES=16*1024*1024, MAX_STRING=2*1024*1024, MAX_MEMBERS=100000;
    private NativeProgressJson() { }

    static JSONObject read(InputStream input) throws Exception {
        try(JsonReader reader=open(input,MAX_BYTES)) {
            Object result=value(reader,0,new int[1]);
            if(!(result instanceof JSONObject)||reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("进度 JSON 格式无效");
            return (JSONObject)result;
        }
    }

    /** For resource reference scans too; oversized strings fail closed and retain their generation. */
    static JsonReader open(InputStream input,final int maximumBytes) {
        InputStream limited=new FilterInputStream(input) {
            private int count;
            private void add(int n)throws IOException {
                if(n>0&&(count+=n)>maximumBytes)throw new IOException("进度文件超过 16 MiB，请分批导入");
            }
            @Override public int read()throws IOException {int n=in.read();if(n!=-1)add(1);return n;}
            @Override public int read(byte[] b,int off,int len)throws IOException {int n=in.read(b,off,len);add(n);return n;}
        };
        // Inspect quoted tokens before JsonReader can build an arbitrarily large String.
        Reader bounded=new FilterReader(new InputStreamReader(limited,java.nio.charset.Charset.forName("UTF-8"))) {
            private boolean quoted,escaped;private int token,unicode;
            private void inspect(char c)throws IOException {
                if(quoted) {
                    if(unicode>0){unicode--;return;}
                    if(escaped){escaped=false;if(c=='u')unicode=4;return;}
                    if(c=='"'){quoted=false;token=0;return;}
                    if(c=='\\')escaped=true;
                    if(++token>MAX_STRING)throw new IOException("单项进度过大，请分批导入");
                } else if(c=='"'){quoted=true;token=0;}
            }
            @Override public int read()throws IOException {int n=super.read();if(n!=-1)inspect((char)n);return n;}
            @Override public int read(char[] b,int off,int len)throws IOException {
                int n=super.read(b,off,len);for(int i=0;i<n;i++)inspect(b[off+i]);return n;
            }
        };
        return new JsonReader(bounded);
    }

    private static Object value(JsonReader reader,int depth,int[] members)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new IOException("操作已取消");
        if(depth>16||++members[0]>MAX_MEMBERS)throw new IOException("进度结构过大或层级过深");
        switch(reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object=new JSONObject();reader.beginObject();
                while(reader.hasNext()) {String key=reader.nextName();if(key.length()>1024)throw new IOException("进度键过长");object.put(key,value(reader,depth+1,members));}
                reader.endObject();return object;
            case BEGIN_ARRAY:
                JSONArray array=new JSONArray();reader.beginArray();
                while(reader.hasNext())array.put(value(reader,depth+1,members));
                reader.endArray();return array;
            case STRING:return reader.nextString();
            case NUMBER:return new JSONTokener(reader.nextString()).nextValue();
            case BOOLEAN:return reader.nextBoolean();
            case NULL:reader.nextNull();return JSONObject.NULL;
            default:throw new IOException("进度 JSON 格式无效");
        }
    }
}


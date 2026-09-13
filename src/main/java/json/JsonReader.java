package json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pull parser over a UTF-8 byte array. Instances are reusable and not thread-safe. */
public final class JsonReader {
    private static final double[] POW10 = {1e0,1e1,1e2,1e3,1e4,1e5,1e6,1e7,1e8,1e9,1e10,1e11,1e12,1e13,1e14,1e15,1e16,1e17,1e18,1e19,1e20,1e21,1e22};
    private byte[] buf;
    private int pos, end, keyStart, keyEnd;
    private char[] chars = new char[64];

    public JsonReader() { this(new byte[0],0,0); }
    public JsonReader(byte[] data) { this(data,0,data.length); }
    public JsonReader(byte[] data,int offset,int length) { reset(data,offset,length); }

    public JsonReader reset(byte[] data,int offset,int length) {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException("offset/length");
        buf=data; pos=offset; end=offset+length; keyStart=keyEnd=pos; return this;
    }
    public JsonReader reset(byte[] data) { return reset(data,0,data.length); }

    public void beginObject() { expect('{'); }
    public void beginArray() { expect('['); }

    public boolean nextKey() {
        byte c=peek();
        if(c=='}'){pos++;return false;}
        if(c==',') { pos++; c=peek(); if(c=='}') throw error("trailing comma in object"); }
        if(c!='"') throw error("expected a field name");
        pos++; keyStart=pos;
        int p=pos;
        while(p<end){ byte x=buf[p]; if(x=='"') break; if(x=='\\'){ p++; if(p>=end) throw errorAt("unterminated field name",p); } else if((x&0xff)<0x20) throw errorAt("control character in field name",p); p++; }
        if(p>=end) throw errorAt("unterminated field name",p);
        keyEnd=p; pos=p+1; if(peek()!=':') throw error("expected ':'"); pos++;
        return true;
    }

    /** Fast raw-byte match for ordinary keys; escaped keys are decoded for semantic equality. */
    public boolean keyIs(JsonField field) {
        byte[] name=field.name;
        if(keyEnd-keyStart==name.length && Arrays.equals(buf,keyStart,keyEnd,name,0,name.length)) return true;
        if(indexOfEscapeOrNonAscii(keyStart,keyEnd)<0) return false;
        return key().equals(field.toString());
    }

    private int indexOfEscapeOrNonAscii(int s,int e){ for(int i=s;i<e;i++){int x=buf[i]&0xff;if(x=='\\'||x>=0x80)return i;} return -1; }

    public String key() {
        int special=indexOfEscapeOrNonAscii(keyStart,keyEnd);
        if(special<0) return new String(buf,keyStart,keyEnd-keyStart,StandardCharsets.US_ASCII);
        return decodeStringRange(keyStart,keyEnd);
    }

    public boolean hasNextElement() {
        byte c=peek();
        if(c==']'){pos++;return false;}
        if(c==','){pos++;c=peek();if(c==']')throw error("trailing comma in array");}
        return true;
    }

    public boolean isNull(){ if(peek()!='n') return false; expectLiteral("null"); return true; }
    public boolean readBoolean(){ byte c=peek(); if(c=='t'){expectLiteral("true");return true;} if(c=='f'){expectLiteral("false");return false;} throw error("expected a boolean"); }

    public int readInt(){ long v=readLong(); if(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE) throw error("integer out of range"); return (int)v; }

    public long readLong(){
        int start=pos; boolean neg=false;
        if(peek()=='-'){neg=true;pos++;} else if(peek() == '+') throw error("'+' is not valid in a JSON number");
        if(pos>=end) throw error("expected a number");
        if(buf[pos]=='0' && pos+1<end && buf[pos+1]>='0' && buf[pos+1]<='9') throw error("leading zero in number");
        if(buf[pos]<'0'||buf[pos]>'9') throw error("expected a number");
        long v=0; int p=pos;
        while(p<end){ int d=buf[p]-'0'; if(d<0||d>9) break; if(v < (Long.MIN_VALUE+d)/10) throw errorAt("integer out of range",p); v=v*10-d; p++; }
        pos=p;
        if(p<end && (buf[p]=='.'||buf[p]=='e'||buf[p]=='E')) throw errorAt("expected an integer",p);
        ensureValueDelimiter();
        return neg ? v : -v;
    }

    public double readDouble(){
        int start=pos; boolean neg=false;
        if(peek()=='-'){neg=true;pos++;} else if(peek()=='+') throw error("'+' is not valid in a JSON number");
        int p=pos;
        if(p>=end||buf[p]<'0'||buf[p]>'9') throw error("expected a number");
        if(buf[p]=='0' && p+1<end && buf[p+1]>='0'&&buf[p+1]<='9') throw errorAt("leading zero in number",p+1);
        while(p<end&&buf[p]>='0'&&buf[p]<='9')p++;
        if(p<end&&buf[p]=='.'){p++;int frac=p;while(p<end&&buf[p]>='0'&&buf[p]<='9')p++;if(p==frac)throw errorAt("digits required after decimal point",p);}
        if(p<end&&(buf[p]=='e'||buf[p]=='E')){p++;if(p<end&&(buf[p]=='+'||buf[p]=='-'))p++;int ep=p;while(p<end&&buf[p]>='0'&&buf[p]<='9')p++;if(p==ep)throw errorAt("digits required after exponent",p);}
        pos=p; ensureValueDelimiter();
        String s=new String(buf,start,p-start,StandardCharsets.US_ASCII);
        double d=Double.parseDouble(s);
        return neg ? -Math.abs(d) : d;
    }

    public String readString(){
        if(peek()=='n'){expectLiteral("null");return null;}
        if(peek()!='"')throw error("expected a string");
        pos++; int start=pos,p=pos;
        while(p<end){int x=buf[p]&0xff;if(x=='"'){if(indexOfEscapeOrNonAscii(start,p)<0){pos=p+1;return new String(buf,start,p-start,StandardCharsets.US_ASCII);}break;}if(x=='\\'||x<0x20||x>=0x80)break;p++;}
        if(p>=end)throw errorAt("unterminated string",p);
        return slowString(start,p);
    }

    /** Validates and consumes one complete value, including matching object/array delimiters. */
    public void skipValue(){
        skipValue0();
    }
    private void skipValue0(){
        byte c=peek();
        if(c=='"'){readString();return;}
        if(c=='{' ){pos++; boolean first=true; while(true){byte x=peek();if(x=='}'){pos++;return;}if(!first){if(x!=',')throw error("expected ',' or '}'");pos++;if(peek()=='}')throw error("trailing comma in object");}if(peek()!='"')throw error("expected a field name");nextKey();skipValue0();first=false;}}
        if(c=='['){pos++;boolean first=true;while(true){byte x=peek();if(x==']'){pos++;return;}if(!first){if(x!=',')throw error("expected ',' or ']'");pos++;if(peek()==']')throw error("trailing comma in array");}skipValue0();first=false;}}
        if(c=='t')expectLiteral("true");
        else if(c=='f')expectLiteral("false");
        else if(c=='n')expectLiteral("null");
        else if(c=='-'||(c>='0'&&c<='9')){int s=pos;while(pos<end){byte x=buf[pos];if(x==','||x=='}'||x==']'||x<=0x20)break;pos++;}String n=new String(buf,s,pos-s,StandardCharsets.US_ASCII);try{Double.parseDouble(n);}catch(NumberFormatException e){throw error("invalid number");}}
        else throw error("invalid value");
        ensureValueDelimiter();
    }

    /** Requires that only JSON whitespace remains. Used by Json.parse to enforce one complete document. */
    public void requireEnd(){ while(pos<end){byte c=buf[pos];if(c==' '||c=='\n'||c=='\r'||c=='\t'){pos++;continue;}throw error("trailing data");} }

    private void ensureValueDelimiter(){ if(pos>=end)return; byte c=buf[pos]; if(c!='}'&&c!=']'&&c!=','&&c!=' '&&c!='\n'&&c!='\r'&&c!='\t') throw error("unexpected character after value"); }

    private String slowString(int start,int p){
        char[] out=chars;if(out.length<32)out=new char[64];int n=0;
        while(p<end){if(n+2>=out.length)out=chars=Arrays.copyOf(out,out.length<<1);int x=buf[p++]&0xff;if(x=='"'){pos=p;return new String(out,0,n);}if(x<0x20)throw errorAt("control character in string",p-1);if(x=='\\'){if(p>=end)throw errorAt("truncated escape",p);int e=buf[p++]&0xff;switch(e){case '"':out[n++]='"';break;case '\\':out[n++]='\\';break;case '/':out[n++]='/';break;case 'b':out[n++]='\b';break;case 'f':out[n++]='\f';break;case 'n':out[n++]='\n';break;case 'r':out[n++]='\r';break;case 't':out[n++]='\t';break;case 'u':if(p+4>end)throw errorAt("truncated \\u escape",p);int cp=(hex(buf[p])<<12)|(hex(buf[p+1])<<8)|(hex(buf[p+2])<<4)|hex(buf[p+3]);p+=4;out[n++]=(char)cp;break;default:throw errorAt("invalid escape",p-1);}continue;}if(x<0x80){out[n++]=(char)x;continue;}int cp;if(x<0xC2)throw errorAt("invalid UTF-8",p-1);if(x<0xE0){if(p>=end)throw errorAt("truncated UTF-8",p);int y=buf[p++]&0xff;if((y&0xC0)!=0x80)throw errorAt("invalid UTF-8",p-1);cp=((x&31)<<6)|(y&63);}else if(x<0xF0){if(p+1>=end)throw errorAt("truncated UTF-8",p);int y=buf[p++]&0xff,z=buf[p++]&0xff;if((y&0xC0)!=0x80||(z&0xC0)!=0x80||x==0xE0&&y<0xA0||x==0xED&&y>=0xA0)throw errorAt("invalid UTF-8",p-2);cp=((x&15)<<12)|((y&63)<<6)|(z&63);}else{if(p+2>=end)throw errorAt("truncated UTF-8",p);int y=buf[p++]&0xff,z=buf[p++]&0xff,q=buf[p++]&0xff;if((y&0xC0)!=0x80||(z&0xC0)!=0x80||(q&0xC0)!=0x80||x>0xF4||x==0xF0&&y<0x90||x==0xF4&&y>=0x90)throw errorAt("invalid UTF-8",p-3);cp=((x&7)<<18)|((y&63)<<12)|((z&63)<<6)|(q&63);cp-=0x10000;out[n++]=(char)(0xD800|(cp>>10));out[n++]=(char)(0xDC00|(cp&0x3ff));continue;}out[n++]=(char)cp;}
        throw errorAt("unterminated string",p);
    }

    private String decodeStringRange(int start,int limit){
        int save=pos;pos=start;String s=slowRange(start,limit);pos=save;return s;
    }
    private String slowRange(int start,int limit){
        char[] out=chars;if(out.length<(limit-start)+8)out=chars=new char[Math.max(64,(limit-start)*2)];int n=0,p=start;
        while(p<limit){int x=buf[p++]&0xff;if(x=='\\'){if(p>=limit)throw errorAt("truncated escape in key",p);int e=buf[p++]&0xff;switch(e){case '"':out[n++]='"';break;case '\\':out[n++]='\\';break;case '/':out[n++]='/';break;case 'b':out[n++]='\b';break;case 'f':out[n++]='\f';break;case 'n':out[n++]='\n';break;case 'r':out[n++]='\r';break;case 't':out[n++]='\t';break;case 'u':if(p+4>limit)throw errorAt("truncated \\u escape in key",p);out[n++]=(char)((hex(buf[p])<<12)|(hex(buf[p+1])<<8)|(hex(buf[p+2])<<4)|hex(buf[p+3]));p+=4;break;default:throw errorAt("invalid escape in key",p-1);} }else if(x<0x80)out[n++]=(char)x;else{int need=x<0xE0?1:x<0xF0?2:3;if(p+need>limit)throw errorAt("truncated UTF-8 in key",p);int cp;if(need==1){int y=buf[p++]&255;if((y&192)!=128)throw errorAt("invalid UTF-8 in key",p-1);cp=((x&31)<<6)|(y&63);}else if(need==2){int y=buf[p++]&255,z=buf[p++]&255;if((y&192)!=128||(z&192)!=128)throw errorAt("invalid UTF-8 in key",p-2);cp=((x&15)<<12)|((y&63)<<6)|(z&63);}else{int y=buf[p++]&255,z=buf[p++]&255,q=buf[p++]&255;if((y&192)!=128||(z&192)!=128||(q&192)!=128||x>244)throw errorAt("invalid UTF-8 in key",p-3);cp=((x&7)<<18)|((y&63)<<12)|((z&63)<<6)|(q&63);cp-=0x10000;out[n++]=(char)(0xD800|(cp>>10));out[n++]=(char)(0xDC00|(cp&1023));continue;}out[n++]=(char)cp;}}
        return new String(out,0,n);
    }

    private int hex(byte c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;throw error("invalid hex digit");}
    private byte peek(){int p=pos;while(p<end){byte c=buf[p];if(c==' '||c=='\n'||c=='\r'||c=='\t'){p++;continue;}pos=p;return c;}pos=end;throw error("unexpected end of input");}
    private void expect(char c){if(peek()!=c)throw error("expected '"+c+"'");pos++;}
    private void expectLiteral(String lit){if(pos+lit.length()>end)throw error("expected '"+lit+"'");for(int i=0;i<lit.length();i++)if(buf[pos+i]!=lit.charAt(i))throw error("expected '"+lit+"'");pos+=lit.length();}
    private JsonException error(String m){return errorAt(m,pos);}
    private JsonException errorAt(String m,int p){return new JsonException(m+" at offset "+p);}
}

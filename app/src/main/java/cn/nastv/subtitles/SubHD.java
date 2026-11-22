package cn.nastv.subtitles;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the public search and detail pages. The website runs its own interactive download flow. */
final class SubHD {
    private static final String HOME="https://subhd.me";
    static final String USER_AGENT="NasSubtitlesTV/1.4";
    static final int MAX_PACK_BYTES=12*1024*1024;
    private static final Set<String> DOMAINS=new HashSet<>(Arrays.asList("subhd.me","subhd.one","subhd.cc","subhd.top","subhd.tv","subhd.com","subhdtw.com"));
    private static final Pattern SEASON=Pattern.compile("(?i)第\\s*([零〇一二两三四五六七八九十百0-9]+)\\s*季|(?:\\bseason[ ._-]*|(?<![a-z0-9])s)(\\d{1,2})(?!\\d)");
    private static final Pattern EP=Pattern.compile("(?i)s(\\d{1,2})[ ._-]*e(\\d{1,3})(?!\\d)|(\\d{1,2})x(\\d{1,3})(?!\\d)");
    private static final Pattern SHORT_EP=Pattern.compile("(?i)(?:第\\s*(\\d{1,3})\\s*集|(?:^|[^a-z0-9])(?:e|ep)[ ._-]*(\\d{1,3})(?!\\d))");
    private static final Pattern GROUP=Pattern.compile("(?i)合集|全集|整季|全季|complete|pack|e\\d+[ ._-]*(?:至|~|-)\\s*(?:e)?\\d+");
    private static final Pattern YEAR=Pattern.compile("[ （(\\[]+(?:19|20)\\d{2}[）)\\] ]*$");
    private SubHD(){}

    static final class Pack {
        final String url,label,detail;
        final List<String> aliases;
        Pack(String url,String label,String detail,List<String> aliases) {
            this.url=url; this.label=label; this.detail=detail; this.aliases=Collections.unmodifiableList(aliases);
        }
    }

    private static class Listing {
        final String url, name;
        final int priority;
        Listing(String url,String name,int priority){this.url=url;this.name=name;this.priority=priority;}
    }

    static boolean official(String raw){
        try {
            URI uri=new URI(raw);
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getRawUserInfo()!=null||
                    (uri.getPort()!=-1&&uri.getPort()!=443))return false;
            host=host.toLowerCase(Locale.ROOT);
            for(String domain:DOMAINS)if(host.equals(domain)||host.endsWith("."+domain))return true;
        }catch(Exception ignored){}
        return false;
    }

    static List<Pack> search(String title,int season) throws IOException {
        String cleaned=YEAR.matcher(title.trim()).replaceFirst("").trim();
        if(cleaned.isEmpty()||cleaned.length()>180||season<0||season>99)throw new IOException("请先选择带有剧名和季号的剧集。");
        LinkedHashMap<String,Listing> candidates=new LinkedHashMap<>();
        Set<String> inspected=new HashSet<>();
        List<Pack> packs=new ArrayList<>();
        IOException last=null;
        String[] terms={cleaned,cleaned+String.format(Locale.ROOT," S%02d",season)};
        for(String term:terms) {
            String path="/search/"+URLEncoder.encode(term,"UTF-8").replace("+","%20");
            String url=HOME+path;
            for(int page=1;page<=2&&url!=null;page++){
                Document doc=page(url);
                if(doc.selectFirst("h4") == null && doc.selectFirst("a[href^=/a/]")==null)
                    throw new IOException("SubHD 未返回搜索列表，请稍后重试。");
                for(Element link:doc.select(".view-text a[href^=/a/]")){
                    Element card=link.closest(".bg-white.shadow-sm");
                    if(card==null)continue;
                    Element image=card.selectFirst("img[alt]");
                    String media=image==null?"":image.attr("alt");
                    Element lang=card.selectFirst(".text-truncate.py-2");
                    if(lang==null||!lang.text().contains("简体")||!matchesShow(cleaned,media))continue;
                    String edition=link.text();
                    if(!sameSeason(season,media,edition))continue;
                    if(EP.matcher(edition).find()&&!GROUP.matcher(edition).find())continue;
                    String href=link.absUrl("href");
                    if(!validDetail(href))continue;
                    int priority=GROUP.matcher(edition).find()?0:1;
                    candidates.putIfAbsent(href,new Listing(href,media,priority));
                }
                String next=null;
                for(Element p:doc.select("a.page-link[href]")){
                    URI u=URI.create(p.absUrl("href"));
                    if(official(u.toString())&&u.getRawPath().equals(path+"/"+(page+1))){next=u.toString();break;}
                }
                url=next;
            }
            List<Listing> ordered=new ArrayList<>(candidates.values());
            ordered.sort(Comparator.comparingInt(row->row.priority));
            for(Listing entry:ordered){
                if(!inspected.add(entry.url))continue;
                if(inspected.size()>18){
                    if(!packs.isEmpty())return packs;
                    throw new IOException("已核查 18 个候选，暂未确认同季简中字幕包。");
                }
                try{
                    Pack pack=detail(page(entry.url),entry.url,cleaned,season);
                    if(pack!=null)packs.add(pack);
                }catch(IOException e){if(Thread.currentThread().isInterrupted())throw e;last=e;}
                if(packs.size()>=8)return packs;
            }
            if(!packs.isEmpty())break;
        }
        if(packs.isEmpty()&&last!=null)throw new IOException("SubHD 字幕包清单未能完整读取。"+last.getMessage(),last);
        return packs;
    }

    private static boolean validDetail(String url){
        return official(url)&&URI.create(url).getPath().matches("/a/[a-zA-Z0-9]+/?");
    }

    static Pack detail(Document doc,String url,String requested,int season) throws IOException{
        Element title=doc.selectFirst("h1 a[href^=/d/]");
        Element lang=doc.selectFirst(".subtitle-metadata-tags");
        Element edition=doc.selectFirst(".subtitle-edition");
        if(title==null||lang==null||edition==null)throw new IOException("SubHD 字幕包页面格式有变。");
        String series=YEAR.matcher(title.text()).replaceFirst("").trim();
        if(!matchesShow(requested,series)||!sameSeason(season,series,edition.text())||!lang.text().contains("简体"))return null;
        if(!validDetail(url)||doc.selectFirst(".subtitle-prepare-download")==null)return null;
        Set<Integer> numbers=new HashSet<>();
        for(Element file:doc.select(".subtitle-file-name")){
            String name=file.hasAttr("data-filename")?file.attr("data-filename"):file.text();
            if(!name.matches("(?i).*\\.(srt|ass|ssa|vtt)$"))continue;
            Matcher ep=EP.matcher(name);
            if(ep.find()){
                int s=Integer.parseInt(ep.group(1)==null?ep.group(3):ep.group(1));
                if(s==season)numbers.add(Integer.parseInt(ep.group(2)==null?ep.group(4):ep.group(2)));
            }else if(hasSeason(season,series+" "+edition.text())){
                Matcher e=SHORT_EP.matcher(name);
                if(e.find())numbers.add(Integer.parseInt(e.group(1)==null?e.group(2):e.group(1)));
                else if(name.matches("(?i)\\d{1,3}\\.(srt|ass|ssa|vtt)"))numbers.add(Integer.parseInt(name.substring(0,name.indexOf('.'))));
            }
        }
        if(numbers.size()<2)return null;
        String meta="第 "+season+" 季 · "+numbers.size()+" 集 · "+lang.text();
        Element user=doc.selectFirst(".subtitle-publisher-row a.fw-bold[href^=/u/]");
        if(user!=null)meta+=" · "+user.text();
        LinkedHashSet<String> aliases=new LinkedHashSet<>();
        aliases.add(requested);aliases.addAll(aliases(series));
        return new Pack(url,edition.text(),meta,new ArrayList<>(aliases));
    }

    private static boolean sameSeason(int season,String series,String edition){
        Matcher m=SEASON.matcher(series+" "+edition);
        while(m.find()){
            int s=m.group(1)==null?Integer.parseInt(m.group(2)):chineseNumber(m.group(1));
            if(s!=season)return false;
        }
        return true;
    }
    private static boolean hasSeason(int season,String text){return SEASON.matcher(text).find()&&sameSeason(season,text,"");}
    private static int chineseNumber(String token){
        if(token.matches("\\d+")){try{return Integer.parseInt(token);}catch(NumberFormatException ignored){return -1;}}
        String digits="零一二三四五六七八九";token=token.replace('两','二').replace('〇','零');
        int tens=token.indexOf('十');
        if(tens<0)return token.length()==1?digits.indexOf(token.charAt(0)):-1;
        int hi=tens==0?1:digits.indexOf(token.charAt(0));
        int lo=tens==token.length()-1?0:digits.indexOf(token.charAt(tens+1));
        return hi*10+lo;
    }
    private static List<String> aliases(String series){
        String s=SEASON.matcher(YEAR.matcher(series.trim()).replaceFirst("")).replaceAll(" | ");
        String split=s.replaceAll("(?<=[\\p{IsHan}])\\s+(?=[A-Za-z])|(?<=[A-Za-z])\\s+(?=[\\p{IsHan}])"," | ");
        List<String> parts=new ArrayList<>();
        for(String part:split.split("\\|"))if(!part.trim().isEmpty())parts.add(part.trim());
        return parts;
    }
    private static boolean matchesShow(String request,String series){
        String wanted=key(request);
        if(wanted.isEmpty())return false;
        for(String alias:aliases(series))if(key(alias).equals(wanted))return true;
        return key(series).equals(wanted);
    }
    private static String key(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}

    private static Document page(String url)throws IOException{
        Reply r=fetch(url,"",USER_AGENT,false);
        return Jsoup.parse(new String(r.bytes,StandardCharsets.UTF_8),r.url);
    }
    static final class Reply{
        final byte[] bytes;final String url,disposition;
        Reply(byte[] bytes,String url,String disposition){this.bytes=bytes;this.url=url;this.disposition=disposition;}
    }
    interface Cookies {String get(String url);}
    static Reply fetch(String url,String referring,String userAgent,boolean archive) throws IOException {
        return fetch(url,referring,userAgent,archive,null);
    }
    static Reply fetch(String url,String referring,String userAgent,boolean archive,Cookies cookies) throws IOException{
        int limit=archive?MAX_PACK_BYTES:2*1024*1024;
        for(int i=0;i<=5;i++){
            if(!official(url))throw new IOException("SubHD 返回了未识别的下载域名。");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            HttpURLConnection conn=(HttpURLConnection)URI.create(url).toURL().openConnection();
            try{
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(10000);conn.setReadTimeout(13000);
                conn.setRequestProperty("User-Agent",userAgent);conn.setRequestProperty("Accept",archive?"*/*":"text/html");
                if(cookies!=null){String cookie=cookies.get(url);if(cookie!=null&&!cookie.isEmpty())conn.setRequestProperty("Cookie",cookie);}
                if(!referring.isEmpty()&&official(referring)){
                    URI ref=URI.create(referring);
                    conn.setRequestProperty("Referer",ref.getHost().equalsIgnoreCase(URI.create(url).getHost())?
                            referring:ref.getScheme()+"://"+ref.getHost()+"/");
                }
                int code=conn.getResponseCode();
                if(code>=300&&code<400){
                    String loc=conn.getHeaderField("Location");if(loc==null)throw new IOException("SubHD 下载地址缺失。");
                    url=URI.create(url).resolve(loc.replace(" ","%20")).toString();continue;
                }
                if(code!=200)throw new IOException("SubHD 返回 HTTP "+code+"。");
                if(conn.getContentLengthLong()>limit)throw new IOException("SubHD 返回文件过大。");
                if(archive && String.valueOf(conn.getContentType()).toLowerCase(Locale.ROOT).contains("text/html"))
                    throw new IOException("SubHD 返回了网页，请在官网完成验证后重新下载。");
                try(InputStream input=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[16384];int n;
                    while((n=input.read(buffer))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                        if(out.size()+n>limit)throw new IOException("SubHD 返回文件过大。");
                        out.write(buffer,0,n);
                    }
                    return new Reply(out.toByteArray(),url,conn.getHeaderField("Content-Disposition"));
                }
            }finally{conn.disconnect();}
        }
        throw new IOException("SubHD 跳转次数过多。");
    }
}

package com.zfdang.zsmth_android.newsmth;

import android.content.Context;
import android.util.Log;

import com.franmontiel.persistentcookiejar.ClearableCookieJar;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.StringUtils;
import com.zfdang.zsmth_android.models.Board;
import com.zfdang.zsmth_android.models.BoardListContent;
import com.zfdang.zsmth_android.models.BoardSection;
import com.zfdang.zsmth_android.models.Post;
import com.zfdang.zsmth_android.models.Topic;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import rx.Observable;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by zfdang on 2016-3-16.
 */
public class SMTHHelper {

    static final private String TAG = "SMTHHelper";
    public static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/36.0.1985.143 Safari/537.36";

    // WWW service of SMTH
    private final String SMTH_WWW_URL = "http://www.newsmth.net";
    static private final String SMTH_WWW_ENCODING = "GB2312";
    private Retrofit mRetrofit = null;
    public SMTHWWWService wService = null;

    // Mobile service of SMTH
    private final String SMTH_MOBILE_URL = "http://m.newsmth.net";
    private Retrofit wRetrofit = null;
    public SMTHMobileService mService = null;

    // All boards cache file
    public static int BOARD_TYPE_FAVORITE = 1;
    public static int BOARD_TYPE_ALL = 2;
    static private final String ALL_BOARD_CACHE_FILE = "SMTH_ALL_BOARDS_CACHE";
    static private final String FAVORITE_BOARD_CACHE_PREFIX = "SMTH_FAVORITE_CACHE";

    // singleton
    private static SMTHHelper instance = null;

    public static SMTHHelper getInstance() {
        if(instance == null) {
            instance = new SMTHHelper(SMTHApplication.getAppContext());
        }
        return instance;
    }

    // response from WWW is GB2312, we need to conver it to UTF-8
    public static String DecodeResponseFromWWW(byte[] bytes) {
        String result = null;
        try {
            result = new String(bytes, SMTH_WWW_ENCODING);
        } catch (UnsupportedEncodingException e) {
            Log.d("DecodeResponseFromWWW", e.toString());
        }
        return result;
    }

    // protected constructor, can only be called by getInstance
    protected SMTHHelper(Context context) {

        // set your desired log level
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        // https://github.com/franmontiel/PersistentCookieJar
        // A persistent CookieJar implementation for OkHttp 3 based on SharedPreferences.
        ClearableCookieJar cookieJar =
                new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context));

        //设置缓存路径
        File httpCacheDirectory = new File(SMTHApplication.getAppContext().getCacheDir(), "Responses");
        int cacheSize = 100 * 1024 * 1024; // 100 MiB
        Cache cache = new Cache(httpCacheDirectory, cacheSize);

        OkHttpClient httpClient = new OkHttpClient().newBuilder()
                .addInterceptor(logging)
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request()
                                .newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .build();
                        return chain.proceed(request);
                    }
                })
                .cookieJar(cookieJar)
                .cache(cache)
                .build();

        mRetrofit = new Retrofit.Builder()
                .baseUrl(SMTH_MOBILE_URL)
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .addConverterFactory(ScalarsConverterFactory.create())
                .client(httpClient)
                .build();
        mService = mRetrofit.create(SMTHMobileService.class);

        wRetrofit = new Retrofit.Builder()
                .baseUrl(SMTH_WWW_URL)
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .client(httpClient)
                .build();
        wService = wRetrofit.create(SMTHWWWService.class);
    }

    // query active user status
    // since wService.queryActiveUserStatus does not return correct faceurl, try to query user information again
    public static Observable<UserStatus> queryActiveUserStatus() {
        final SMTHHelper helper = SMTHHelper.getInstance();
        return helper.wService.queryActiveUserStatus()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .map(new Func1<UserStatus, UserStatus>() {
                    @Override
                    public UserStatus call(UserStatus userStatus) {
                        String userid = userStatus.getId();
                        if(userid != null && !userid.equals("guest")) {
                            // get correct faceURL
                            List<UserInfo> users = helper.wService.queryUserInformation(userid).toList().toBlocking().single();
                            if(users.size() == 1) {
                                UserInfo user = users.get(0);
                                userStatus.setFace_url(user.getFace_url());
                            }
                        }
                        return userStatus;
                    }
                });

    }

    public static Observable<String> publishPost(String boardEngName,
                                                 String subject,
                                                 String content,
                                                 String signature,
                                                 String replyPostID){
        SMTHHelper helper = SMTHHelper.getInstance();
        return helper.wService.publishPost(boardEngName, subject, content, signature, replyPostID)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .map(new Func1<AjaxResponse, String>() {
                    @Override
                    public String call(AjaxResponse ajaxResponse) {
                        try{
                            String response = ajaxResponse.getAjax_msg();
                            return response;
                        } catch (Exception e) {
                            Log.d(TAG, Log.getStackTraceString(e));
                        }
                        return null;
                    };
                });
    }


    public static List<Post> ParsePostListFromWWW(String content, Topic topic) {
        final String TAG = "ParsePostListFromWWW";
        List<Post> results = new ArrayList<>();

        Document doc = Jsoup.parse(content);

        // find total posts for this topic, and total pages
        Elements lis = doc.select("li.page-pre");
        if(lis.size() > 0) {
            Element li = lis.first();
            // 贴数:152 分页:
//            Log.d(TAG, li.text());

            Pattern pattern = Pattern.compile("(\\d+)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(li.text());
            if (matcher.find()) {
                String totalPostString = matcher.group(0);
                topic.setTotalPostNoFromString(totalPostString);
//                Log.d(TAG, totalPostString);
            }
        }

        // find all posts
        Elements tables = doc.select("table.article");
        for (Element table: tables) {
            Post post = new Post();

            // find author for this post
            // <span class="a-u-name"><a href="/nForum/user/query/CZB">CZB</a></span>
            Elements authors = table.select("span.a-u-name");
            if(authors.size() > 0){
                Element author = authors.get(0);
                String authorName = author.text();
                post.setAuthor(authorName);
//                Log.d(TAG, authorName);
            }

            // find post id for this post
            // <samp class="ico-pos-reply"></samp><a href="/nForum/article/WorkLife/post/1113865" class="a-post">回复</a></li>
            Elements links = table.select("li a.a-post");
            if(links.size() > 0){
                Element link = links.first();
                String postID = StringUtils.getLastStringSegment(link.attr("href"));
                post.setPostID(postID);
//                Log.d(TAG, postID);
            }

            // find & parse post content
            Elements contents = table.select("td.a-content");
            if(contents.size() == 1) {
                ParsePostContentFromWWW(contents.get(0), post);
            }
//            Log.d(TAG, post.toString());
            results.add(post);
        }

        if(results.size() == 0) {
            // there might be some problems with the response
//            <div class="error">
//            <h5>产生错误的可能原因：</h5>
//            <ul>
//            <li>
//            <samp class="ico-pos-dot"></samp>指定的文章不存在或链接错误</li>
//            </ul>
//            </div>
            Elements divs = doc.select("div.error");
            if(divs.size() > 0) {
                Element div = divs.first();

                topic.setTotalPostNoFromString("1");

                Post post = new Post();
                post.setAuthor("错误信息");
                post.setRawContent(div.toString());
                results.add(post);
            }



        }

        return results;
    }

    // called by ParsePostListFromWWW
    // this method will call ParseLikeElementInPostContent & ParsePostBodyFromWWW
    // sample response: assets/post_content_from_www.html
    public static void ParsePostContentFromWWW(Element content, Post post) {
        // 1. find, parse and remove likes node first
        // <div class="likes">
        Elements nodes = content.select("div.likes");
        List<String> likes = null;
        if(nodes.size() == 1) {
            Element node = nodes.first();
            likes = ParseLikeElementInPostContent(node);
        }

        // 2. find post content, the first <p> node in the td.a-content
        // <button class="button add_like"
        nodes = content.getElementsByTag("p");
        if(nodes.size() >= 1) {
            Element node = nodes.first();
            // 2. set post content
            post.setLikesAndPostContent(likes, node);
        }
    }

    // parse like list in post content
    public static List<String> ParseLikeElementInPostContent(Element like) {
        List<String> likes = new ArrayList<>();

        // <div class="like_name">有36位用户评价了这篇文章：</div>
        Elements nodes = like.select("div.like_name");
        if(nodes.size() == 1) {
            Element node = nodes.first();
            likes.add(node.text());
        }

        // <li><span class="like_score_0">[&nbsp;&nbsp;]</span><span class="like_user">fly891198061:</span>
        // <span class="like_msg">无法忍受，我不会变节，先斗智，不行就自杀！来个痛快的~！</span>
        // <span class="like_time">(2016-03-27 15:04)</span></li>
        nodes = like.select("li");
        for(Element n: nodes) {
            likes.add(n.text());
        }

        return likes;
    }



    // [团购]3.28-4.03 花的传说饰品团购(18) ==> 18
    public static String getReplyCountInParentheses(String content) {
        Pattern hp = Pattern.compile("\\((\\d+)\\)$", Pattern.DOTALL);
        Matcher hm = hp.matcher(content);
        if (hm.find()) {
            String count = hm.group(1);
            return count;
        }

        return "";
    }


    public static Topic ParseTopicFromElement(Element ele, String type) {
        if("top10".equals(type) || "hotspot".equals(type) || "sectionhot".equals(type)) {
            // two <A herf> nodes

            // normal hot topic
            // <li><a href="/nForum/article/OurEstate/1685281" title="lj让我走垫资(114)">lj让我走垫资&nbsp;(114)</a></li>

            // special hot topic -- 近期热帖: 1. board信息，没有reply_count
            // <li>
            // <div><a href="/nForum/board/Picture"><span class="board">[贴图]</span></a><a href="/nForum/article/ShiDa/59833" title=" 南都副总编及编辑被处分开除"><span class="title"> 南都副总编及编辑被处分开除</span></a></div>
            // </li>

            Elements as = ele.select("a[href]");
            if(as.size() == 2) {
                Element a1 = as.get(0);
                Element a2 = as.get(1);

                String boardChsName = a1.text().replace("]", "").replace("[", "");
                String boardEngName = StringUtils.getLastStringSegment(a1.attr("href"));

                String title = a2.attr("title");
                String topicID = StringUtils.getLastStringSegment(a2.attr("href"));

                Topic topic = new Topic();
                String reply_count = getReplyCountInParentheses(title);
                if(reply_count.length() > 0) {
                    title = title.substring(0, title.length() - reply_count.length() - 2);
                    topic.setTotalPostNoFromString(reply_count);
                }

                topic.setBoardEngName(boardEngName);
                topic.setBoardChsName(boardChsName);
                if("hotspot".equals(type)) {
                    topic.setIsShida(true);
                }
                topic.setTopicID(topicID);
                topic.setTitle(title);

//                Log.d(TAG, topic.toString());
                return topic;
            }
        } else if("pictures".equals(type)) {
            // three <A herf> nodes

            // <li>
            // <a href="/nForum/article/SchoolEstate/430675"><img src="http://images.newsmth.net/nForum/img/hotpic/SchoolEstate_430675.jpg" title="点击查看原帖" /></a>
            // <br /><a class="board" href="/nForum/board/SchoolEstate">[学区房]</a>
            // <br /><a class="title" href="/nForum/article/SchoolEstate/430675" title="这个小学排名还算靠谱吧， AO爸爸排的。。。">这个小学排名还算靠谱吧， AO爸爸排的。。。</a>
            // </li>
            Elements as = ele.select("a[href]");
            if(as.size() == 3) {
                Element a1 = as.get(1);
                Element a2 = as.get(2);

                String boardChsName = a1.text().replace("]", "").replace("[", "");
                String boardEngName = StringUtils.getLastStringSegment(a1.attr("href"));

                String title = a2.attr("title");
                String topicID = StringUtils.getLastStringSegment(a2.attr("href"));


                Topic topic = new Topic();
                topic.setBoardEngName(boardEngName);
                topic.setBoardChsName(boardChsName);
                topic.setTopicID(topicID);
                topic.setTitle(title);

//                Log.d(TAG, topic.toString());
                return topic;
            }

        }
        return null;
    }


    // parse guidance page, to find all hot topics
    public static List<Topic> ParseHotTopicsFromWWW(String content) {
        List<Topic> results = new ArrayList<>();
        if (content == null || content.length() == 0) {
            return results;
        }

        Topic topic = null;
        Document doc = Jsoup.parse(content);

        // find top10
        // <div id="top10">
        Elements top10s = doc.select("div#top10");
        if(top10s.size() == 1) {
            // add separator
            topic = new Topic("本日十大热门话题");
            results.add(topic);

            // parse hot hopic
            Element top10 = top10s.first();
            Elements lis = top10.getElementsByTag("li");

            for(Element li: lis) {
                topic = ParseTopicFromElement(li, "top10");
                if(topic != null) {
//                    Log.d(TAG, topic.toString());
                    results.add(topic);
                }
            }
        }


        // find hotspot
        // <div id="hotspot" class="block">
        // skip this part, it's tedious
//        Elements hotspots = doc.select("div#hotspot div.topics");
//        if(hotspots.size() == 1) {
//            // add separator
//            topic = new Topic("近期热帖");
//            results.add(topic);
//
//            // parse hot hopic
//            Element hotspot = hotspots.first();
//            Elements lis = hotspot.getElementsByTag("li");
//
//            for(Element li: lis) {
//                topic = ParseTopicFromElement(li, "hotspot");
//                if(topic != null) {
////                    Log.d(TAG, topic.toString());
//                    results.add(topic);
//                }
//            }
//        }

        // find hot picture
        // <div id="pictures" class="block">
        Elements pictures = doc.select("div#pictures");
        for(Element section: pictures) {
            // add separator
            Elements sectionNames = section.getElementsByTag("h3");
            if(sectionNames.size() == 1) {
                Element sectionName = sectionNames.first();
                topic = new Topic(sectionName.text());
                results.add(topic);
            }

            Elements lis = section.select("div li");
            for (Element li: lis) {
//                Log.d(TAG, li.toString());
                topic = ParseTopicFromElement(li, "pictures");
                if(topic != null) {
//                    Log.d(TAG, topic.toString());
                    results.add(topic);
                }
            }
        }

        // find hot topics from each section
        // <div id="hotspot" class="block">
        Elements sections = doc.select("div.b_section");
        for(Element section: sections) {
            // add separator
            Elements sectionNames = section.getElementsByTag("h3");
            if(sectionNames.size() == 1) {
                Element sectionName = sectionNames.first();
                String name = sectionName.text();
                if(name != null && name.equals("系统与祝福")){
                    continue;
                }
                topic = new Topic(name);
                results.add(topic);
            }

            Elements lis = section.select("div.topics li");
            for (Element li: lis) {
//                Log.d(TAG, li.toString());
                topic = ParseTopicFromElement(li, "sectionhot");
                if(topic != null) {
//                    Log.d(TAG, topic.toString());
                    results.add(topic);
                }
            }
        }

        return results;
    }


    // parse board topics from mobile
    public static List<Topic> ParseBoardTopicsFromMobile(String content) {
        List<Topic> results = new ArrayList<>();
        if (content == null) {
            return results;
        }

        Log.d("ParseBoardTopics", content);

        // <a class="plant">1/1272</a> 当前页/总共页
        Pattern pagePattern = Pattern.compile("<a class=\"plant\">(\\d+)/(\\d+)");
        Matcher pageMatcher = pagePattern.matcher(content);
        if (pageMatcher.find()) {
            int currentPageNo = Integer.parseInt(pageMatcher.group(1));
            int totalPageNo = Integer.parseInt(pageMatcher.group(2));
            Log.d("ParseBoardTopics", String.format(" %d of %d", currentPageNo, totalPageNo));
        }

//        <ul class="list sec">
//        <li class="hla"><div><a href="/article/DSLR/1700440" class="top">[合集] 水木上的低价单反广告不可信</a>(0)</div><div>2012-10-16&nbsp;<a href="/user/query/yuningilike">yuningilike</a>|2012-10-16&nbsp;<a href="/user/query/yuningilike">yuningilike</a></div></li>
//        <li><div><a href="/article/DSLR/808676907" class="m">谷歌完全免费化专业PS滤镜套装Nik Collection</a>(6)</div><div>09:52:33&nbsp;<a href="/user/query/BEO">BEO</a>|14:22:58&nbsp;<a href="/user/query/yuningilike">yuningilike</a></div></li>
//        </ul>

        // parse topics using Jsoup
        Document doc = Jsoup.parse(content);

        // get all lis
        Elements lis = doc.select("ul li");
        for (Element li: lis) {
//            Log.d("ParseBoardTopics", li.toString());
            Topic topic = new Topic();

            Elements links = li.select("a[href]");
            if(links.size() == 3) {
                Element link1 =  links.get(0);
                Element link2 =  links.get(1);
                Element link3 =  links.get(2);
                String topicID = StringUtils.getLastStringSegment(link1.attr("href"));
                String type = link1.attr("class");
                if("top".equals(type)) {
                    topic.isSticky = true;
                }
                String title = link1.text();
                String author = link2.text();
                String replier = link3.text();

                topic.setAuthor(author);
                topic.setTopicID(topicID);
                topic.setTitle(title);
                topic.setReplier(replier);
            }

            // find dates
            Elements divs = li.select("div");
            if(divs.size() == 2) {
                String temp = divs.get(1).text();
                // temp的样本
                // 2016-03-22 Dd1122Ee|2016-03-23 DRAGON94Dd1122Ee
                // 09:51:35 Qid|11:37:42 Frankiewong4Qid
                String[] tokens = temp.split("[\\|\\s]+");
                if(tokens.length == 4) {
                    String publishDate = tokens[0];
                    String replyDate = tokens[2];

                    topic.setPublishDate(publishDate);
                    topic.setReplyDate(replyDate);
                }
            }

            Log.d("ParseBoardTopics", topic.toString());
            results.add(topic);
        }


        return results;
    }



    public static List<Board> ParseFavoriteBoardsFromWWW(String content) {
        List<Board> boards = new ArrayList<>();

//        o.f(1,'favFolder1 ',0,'');
//        o.o(false,1,896,22556,'[站务]','Advice','水木发展','SYSOP',7026,895,4);
//        o.o(false,1,619,2332235,'[生活]','CouponsLife','辣妈羊毛党','hmilytt XZCL',897207,618,1601);
//        o.o(false,1,179,808676665,'[数码]','DSLR','数码单反','jerryxiao',153110,178,57);

        // 先提取目录
        Pattern pattern = Pattern.compile("o\\.f\\((\\d+),'([^']+)',\\d+,''\\);");
        Matcher matcher = pattern.matcher(content);
//        List<String> list = new ArrayList<String>();
        while (matcher.find()) {
//            list.add(matcher.group(1));
            Board board = new Board(matcher.group(1), matcher.group(2));
            boards.add(board);
        }

        // 再提取收藏的版面
        // o.o(false,1,998,22156,'[站务]','Ask','新用户疑难解答','haning BJH',733,997,0);
        pattern = Pattern.compile("o\\.o\\(\\w+,\\d+,(\\d+),\\d+,'\\[([^']+)\\]','([^']+)','([^']+)','([^']*)',\\d+,\\d+,\\d+\\)");
        matcher = pattern.matcher(content);
        while (matcher.find()) {
            String boardID = matcher.group(1);
            String category = matcher.group(2);
            String engName = matcher.group(3);
            String chsName = matcher.group(4);
            String moderator = matcher.group(5);
            if (moderator.length() > 25) {
                moderator = moderator.substring(0, 21) + "...";
            }
            Board board = new Board(boardID, chsName, engName);
            board.setModerator(moderator);
            board.setCategoryName(category);
            boards.add(board);
        }

        return boards;
    }

    /*
    * All Boards related methods
    * Starts here
     */
    public static String getCacheFile(int type, String folder) {
        if(type == BOARD_TYPE_ALL) {
            return ALL_BOARD_CACHE_FILE;
        } else if (type == BOARD_TYPE_FAVORITE) {
            if(folder == null || folder.length() == 0){
                folder = "ROOT";
            }
            return String.format("%s-%s", FAVORITE_BOARD_CACHE_PREFIX, folder);
        }
        return null;
    }

    public static List<Board> LoadBoardListFromCache(int type, String folder){
        String filename = getCacheFile(type, folder);
        List<Board> boards = new ArrayList<>();
        try {
            FileInputStream is = SMTHApplication.getAppContext().openFileInput(filename);
            ObjectInputStream ois = new ObjectInputStream(is);
            boards = (ArrayList<Board>) ois.readObject();
            is.close();
            Log.d("LoadBoardListFromCache", String.format("%d boards loaded from cache file %s", boards.size(), filename));
        } catch (Exception e) {
            Log.d("LoadBoardListFromCache", e.toString());
            Log.d("LoadBoardListFromCache", "failed to load boards from cache file " + filename);
        }
        return boards;
    }

    public static void SaveBoardListToCache(List<Board> boards, int type, String folder){
        String filename = getCacheFile(type, folder);
        try {
            FileOutputStream fos = SMTHApplication.getAppContext().openFileOutput(filename, Context.MODE_PRIVATE);
            ObjectOutputStream os = new ObjectOutputStream(fos);
            os.writeObject(boards);
            fos.close();
            Log.d("SaveBoardListToCache", String.format("%d boards saved to cache file %s", boards.size(), filename));
        } catch (Exception e) {
            Log.d("SaveBoardListToCache", e.toString());
            Log.d("SaveBoardListToCache", "failed to save boards to cache file " + filename);
        }
    }

    public static void ClearBoardListCache(int type, String folder) {
        String filename = getCacheFile(type, folder);
        try{
            if(SMTHApplication.getAppContext().deleteFile(filename))
            {
                Log.d("ClearBoardListCache", String.format("delete cache file %s successfully", filename));
                return;
            }
        } catch (Exception e) {
            Log.d("ClearBoardListCache", e.toString());
            Log.d("ClearBoardListCache", "Failed to delete cache file " + filename);
        }
    }

    public static List<Board> LoadFavoriteBoardsByFolderFromWWW(final String path) {
        List<Board> results = SMTHHelper.getInstance().wService.getFavoriteByPath(path)
                .flatMap(new Func1<ResponseBody, Observable<Board>>() {
                    @Override
                    public Observable<Board> call(ResponseBody resp) {
                        try {
                            String response = SMTHHelper.DecodeResponseFromWWW(resp.bytes());
//                            Log.d(TAG, response);
                            List<Board> boards = SMTHHelper.ParseFavoriteBoardsFromWWW(response);
                            return Observable.from(boards);
                        } catch (Exception e) {
                            Log.d(TAG, "Failed to load favorite {" + path + "}");
                            Log.d(TAG, e.toString());
                            return null;
                        }
                    }
                })
                .toList().toBlocking().single();

        SaveBoardListToCache(results, BOARD_TYPE_FAVORITE, path);

        return results;
    }


    // load all boards from WWW, recursively
    // http://stackoverflow.com/questions/31246088/how-to-do-recursive-observable-call-in-rxjava
    public static List<Board> LoadAllBoardsFromWWW() {
        final String[] SectionNames = {"社区管理", "国内院校", "休闲娱乐", "五湖四海", "游戏运动", "社会信息", "知性感性", "文化人文", "学术科学", "电脑技术", "终止版面"};
        final String[] SectionURLs = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A"};
        final List<BoardSection> sections = new ArrayList<>();
        for(int index = 0; index < SectionNames.length; index ++) {
            BoardSection section = new BoardSection();
            section.sectionURL = SectionURLs[index];
            section.sectionName = SectionNames[index];
            sections.add(section);
        }

        List<Board>  boards = Observable.from(sections)
                .flatMap(new Func1<BoardSection, Observable<Board>>() {
                    @Override
                    public Observable<Board> call(BoardSection section) {
                        return SMTHHelper.loadBoardsInSectionFromWWW(section);
                    }
                })
                .flatMap(new Func1<Board, Observable<Board>>() {
                    @Override
                    public Observable<Board> call(Board board) {
                        return SMTHHelper.loadChildBoardsRecursivelyFromWWW(board);
                    }
                })
                .filter(new Func1<Board, Boolean>() {
                    @Override
                    public Boolean call(Board board) {
                        // keep board only
                        return !board.isFolder();
                    }
                })
                // http://stackoverflow.com/questions/26311513/convert-observable-to-list
                .toList().toBlocking().single();

        // sort the board list by chinese name
        Collections.sort(boards, new BoardListContent.ChineseComparator());
        Log.d("LoadAllBoardsFromWWW", String.format("%d boards loaded from network", boards.size()));

        // save boards to disk
        SaveBoardListToCache(boards, BOARD_TYPE_ALL, null);

        return boards;
    }

    public static Observable<Board> loadChildBoardsRecursivelyFromWWW(Board board) {
        if(board.isFolder()) {
            BoardSection section = new BoardSection();
            section.sectionURL = board.getFolderID();
            section.sectionName = board.getFolderName();
            section.parentName = board.getCategoryName();

            return SMTHHelper.loadBoardsInSectionFromWWW(section);
        } else {
            return Observable.just(board);
        }
    }


    public static Observable<Board> loadBoardsInSectionFromWWW(final BoardSection section) {
        String sectionURL = section.sectionURL;
        return SMTHHelper.getInstance().wService.getBoardsBySection(sectionURL)
                .flatMap(new Func1<ResponseBody, Observable<Board>>() {
                    @Override
                    public Observable<Board> call(ResponseBody responseBody) {
                        try {
                            String response = responseBody.string();
                            List<Board> boards = SMTHHelper.ParseBoardsInSectionFromWWW(response, section);
                            return Observable.from(boards);

                        } catch (Exception e) {
                            Log.d(TAG, e.toString());
                            return null;
                        }
                    }
                });
    }


    public static List<Board> ParseBoardsInSectionFromWWW(String content, BoardSection section) {
        List<Board> boards = new ArrayList<>();

//        <tr><td class="title_1"><a href="/nForum/section/Association">协会社团</a><br />Association</td><td class="title_2">[二级目录]<br /></td><td class="title_3">&nbsp;</td><td class="title_4 middle c63f">&nbsp;</td><td class="title_5 middle c09f">&nbsp;</td><td class="title_6 middle c63f">&nbsp;</td><td class="title_7 middle c09f">&nbsp;</td></tr>
//        <tr><td class="title_1"><a href="/nForum/board/BIT">北京理工大学</a><br />BIT</td><td class="title_2"><a href="/nForum/user/query/mahenry">mahenry</a><br /></td><td class="title_3"><a href="/nForum/article/BIT/250116">今年几万斤苹果都滞销了，果农欲哭无泪！</a><br />发贴人:&ensp;jingling6787 日期:&ensp;2016-03-22 09:19:09</td><td class="title_4 middle c63f">11</td><td class="title_5 middle c09f">2</td><td class="title_6 middle c63f">5529</td><td class="title_7 middle c09f">11854</td></tr>
//        <tr><td class="title_1"><a href="/nForum/board/Orienteering">定向越野</a><br />Orienteering</td><td class="title_2"><a href="/nForum/user/query/onceloved">onceloved</a><br /></td><td class="title_3"><a href="/nForum/article/Orienteering/59193">圆明园定向</a><br />发贴人:&ensp;jiang2000 日期:&ensp;2016-03-19 14:19:10</td><td class="title_4 middle c63f">0</td><td class="title_5 middle c09f">0</td><td class="title_6 middle c63f">4725</td><td class="title_7 middle c09f">18864</td></tr>

        Document doc = Jsoup.parse(content);
        // get all tr
        Elements trs = doc.select("table.board-list tr");
        for (Element tr: trs) {
//            Log.d("Node", tr.toString());

            Elements t1links = tr.select("td.title_1 a[href]");
            if(t1links.size() == 1) {
                Element link1 = t1links.first();
                String temp = link1.attr("href");

                String chsBoardName = "";
                String engBoardName = "";
                String moderator = "";
                String folderChsName = "";
                String folderEngName = "";

                Pattern boardPattern = Pattern.compile("/nForum/board/(\\w+)");
                Matcher boardMatcher = boardPattern.matcher(temp);
                if (boardMatcher.find()) {
                    engBoardName = boardMatcher.group(1);
                    chsBoardName = link1.text();
                    // it's a normal board
                    Elements t2links = tr.select("td.title_2 a[href]");
                    if(t2links.size() == 1 ) {
                        // if we can find link to moderator, set moderator
                        // it's also possible that moderator is empty, so no link can be found
                        Element link2 = t2links.first();
                        moderator = link2.text();
                    }

                    Board board = new Board("", chsBoardName, engBoardName);
                    board.setModerator(moderator);
                    board.setCategoryName(section.getBoardCategory());
                    boards.add(board);

                }

                Pattern sectionPattern = Pattern.compile("/nForum/section/(\\w+)");
                Matcher sectionMatcher = sectionPattern.matcher(temp);
                if (sectionMatcher.find()) {
                    // it's a section
                    folderEngName = sectionMatcher.group(1);
                    folderChsName = link1.text();

                    Board board = new Board(folderEngName, folderChsName);
                    board.setCategoryName(section.sectionName);
                    boards.add(board);
                }

//                Log.d("parse", String.format("%s, %s, %s, %s, %s", chsBoardName, engBoardName, folderChsName, folderEngName, moderator));
            }

        }

        return boards;
    }
    /*
    * All Boards related methods
    * Ends here
     */

}

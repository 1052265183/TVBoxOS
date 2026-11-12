package com.github.tvbox.osc.base;

import android.app.Activity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Date;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
        neutralizeBrokenDanmakuLib();
    }

    /**
     * 爬虫 jar 会往 cache 目录释放 danMugo_v8.so 并 System.load 它，
     * 该库只有 ARM 指令，在 x86 翻译环境（libndk_translation）下触发 SIGILL 闪退。
     * 这里把文件换成空壳并设为不可写，让爬虫的加载失败后走无弹幕降级路径。
     * 真机（ARM 原生）不需要这个处理，因此仅当检测到翻译层时才执行。
     */
    private void neutralizeBrokenDanmakuLib() {
        if (!isArmTranslated()) {
            return;
        }
        try {
            File cacheDir = getCacheDir();
            File target = new File(cacheDir, "danMugo_v8.so");
            if (target.exists()) {
                target.delete();
            }
            // 建同名目录：爬虫写文件或 dlopen 都会失败，且无法覆盖目录
            if (target.mkdirs()) {
                LOG.e("danmaku lib path occupied by dir");
            }
            LOG.e("danmaku lib neutralized for translated env");
        } catch (Throwable th) {
            LOG.e("neutralizeBrokenDanmakuLib failed: " + th.getMessage());
        }
    }

    /**
     * 判断当前是否运行在 ARM→x86 翻译环境。
     * 真机 ARM 上返回 false，不做任何处理。
     */
    private boolean isArmTranslated() {
        try {
            String abi = android.os.Build.CPU_ABI == null ? "" : android.os.Build.CPU_ABI;
            if (abi.contains("x86")) {
                return true;
            }
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null) {
                for (String a : abis) {
                    if (a != null && a.contains("x86")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        }
        applyDefaultConfigIfNeeded();
        forceSoftDecode();
        logPlayerChoice();
        installCrashLogger();
    }

    /**
     * 把未捕获异常写到 /sdcard/tvbox_crash.txt。
     * 模拟器 logcat 不可用，只能落盘取证。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    FileOutputStream fos = new FileOutputStream("/sdcard/tvbox_crash.txt", true);
                    String head = "\n===== " + new Date() + " thread=" + thread.getName() + " =====\n";
                    fos.write(head.getBytes("UTF-8"));
                    PrintWriter pw = new PrintWriter(fos);
                    ex.printStackTrace(pw);
                    pw.flush();
                    fos.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(thread, ex);
                }
            }
        });
    }

    /**
     * 兆懿 x86 模拟器 libmedia 的 ResourceManagerService::fromBinder 返回 null，
     * ExoPlayer 创建 MediaCodec 时触发 SIGTRAP 崩溃（见 /data/tombstones）。
     * 改用 IJK 软解绕过系统 MediaCodec，真机不受影响。
     */
    /**
     * 把播放器与解码器选择结果落到 /sdcard/tvbox_player.log。
     * 模拟器 logcat 不可用，只能靠落盘确认 selector 是否真的生效。
     */
    private void logPlayerChoice() {
        try {
            FileOutputStream fos = new FileOutputStream("/sdcard/tvbox_player.log", true);
            StringBuilder sb = new StringBuilder();
            sb.append("===== ").append(new Date()).append(" =====\n");
            sb.append("play_type=").append(Hawk.get(HawkConfig.PLAY_TYPE, 2)).append("\n");
            sb.append("ijk_codec=").append(Hawk.get(HawkConfig.IJK_CODEC, "硬解码")).append("\n");
            sb.append("abi=").append(android.os.Build.CPU_ABI).append("\n");
            sb.append("abi2=").append(android.os.Build.CPU_ABI2).append("\n");
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private void forceSoftDecode() {
        int playType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
        // ExoPlayer 配合 C2SoftMediaCodecSelector 走 c2.android.* 原生软解，
        // 既不撞 libmedia 也无需 libhoudini 转译，是模拟器上唯一稳定路径。
        // IJK 属于 ARM 原生库，转译开销大，仅在 ExoPlayer 不可用时才退回。
        if (playType != 2) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
            LOG.e("forceSoftDecode: switch to exo + c2 soft selector");
        }
    }

    /**
     * 首次启动或配置为空时，自动注入预置配置源。
     * 预置路径写在 /sdcard/tvbox_default_config.txt，内容为 clan://localhost/xxx.json 形式，
     * 免去在 TV 遥控器上手动输入 URL 的麻烦。
     */
    private void applyDefaultConfigIfNeeded() {
        String current = Hawk.get(HawkConfig.API_URL, "");
        if (current != null && !current.isEmpty()) {
            return;
        }
        File preset = new File("/sdcard/tvbox_default_config.txt");
        if (!preset.exists()) {
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(preset), "UTF-8"));
            String url = reader.readLine();
            reader.close();
            if (url == null || url.trim().isEmpty()) {
                return;
            }
            url = url.trim();
            Hawk.put(HawkConfig.API_URL, url);
            Hawk.put(HawkConfig.LIVE_API_URL, url);
            LOG.e("preset config applied: " + url);
        } catch (Exception e) {
            LOG.e("applyDefaultConfigIfNeeded failed: " + e.getMessage());
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
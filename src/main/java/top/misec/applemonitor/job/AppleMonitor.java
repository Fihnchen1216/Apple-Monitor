package top.misec.applemonitor.job;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.cookie.GlobalCookieManager;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import top.misec.applemonitor.config.*;
import top.misec.applemonitor.push.impl.FeiShuBotPush;
import top.misec.applemonitor.push.pojo.feishu.FeiShuPushDTO;
import top.misec.bark.BarkPush;
import top.misec.bark.enums.SoundEnum;
import top.misec.bark.pojo.PushDetails;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author MoshiCoCo
 */
@Slf4j
public class AppleMonitor {

    static {
        // 全局启用 Cookie 管理器（保存 Set-Cookie）
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        GlobalCookieManager.setCookieManager(cm);
    }

    private final AppCfg CONFIG = CfgSingleton.getInstance().config;

    public void monitor() {

        List<DeviceItem> deviceItemList = CONFIG.getAppleTaskConfig().getDeviceCodeList();
        //监视机型型号

        try {
            for (DeviceItem deviceItem : deviceItemList) {
                doMonitor(deviceItem);
                Thread.sleep(ThreadLocalRandom.current().nextInt(1500, 4500));
            }
        } catch (Exception e) {
            log.error("AppleMonitor Error", e);
        }
    }


    public void pushAll(String content, List<PushConfig> pushConfigs) {

        pushConfigs.forEach(push -> {

            if (StrUtil.isAllNotEmpty(push.getBarkPushUrl(), push.getBarkPushToken())) {
                BarkPush barkPush = new BarkPush(push.getBarkPushUrl(), push.getBarkPushToken());
                PushDetails pushDetails= PushDetails.builder()
                        .title("苹果商店监控")
                        .body(content)
                        .category("苹果商店监控")
                        .group("Apple Monitor")
                        .sound(StrUtil.isEmpty(push.getBarkPushSound()) ? SoundEnum.GLASS.getSoundName() : push.getBarkPushSound())
                        .build();
                barkPush.simpleWithResp(pushDetails);
            }
            if (StrUtil.isAllNotEmpty(push.getFeishuBotSecret(), push.getFeishuBotWebhooks())) {

                FeiShuBotPush.pushTextMessage(FeiShuPushDTO.builder()
                        .text(content).secret(push.getFeishuBotSecret())
                        .botWebHooks(push.getFeishuBotWebhooks())
                        .build());
            }
        });

    }

    public void doMonitor(DeviceItem deviceItem) {

        Map<String, Object> queryMap = new HashMap<>(5);
        queryMap.put("pl", "true");
        queryMap.put("mts.0", "regular");
        queryMap.put("parts.0", deviceItem.getDeviceCode());
        queryMap.put("location", CONFIG.getAppleTaskConfig().getLocation());

        String baseCountryUrl = CountryEnum.getUrlByCountry(CONFIG.getAppleTaskConfig().getCountry());

        //Map<String, List<String>> headers = buildHeaders(baseCountryUrl, deviceItem.getDeviceCode());

        String apiUrl = baseCountryUrl + "/shop/fulfillment-messages?" + URLUtil.buildQuery(queryMap, CharsetUtil.CHARSET_UTF_8);

        try {
            final String refererPage = baseCountryUrl + "/shop/buy-iphone";
            try (HttpResponse warm = HttpRequest.get(refererPage)
                    .addHeaders(buildUSBrowserHeaders(false))
                    .timeout(8000)
                    .execute()) {
                // Cookie 已写入 CookieJar
            }

            // 2) 调用前随机抖动 3~12 秒，避免固定频率触发风控
            Thread.sleep(ThreadLocalRandom.current().nextInt(3000, 12000));

            // 3) 调 fulfillment API（浏览器式请求头）
            JSONObject responseJsonObject;
            try (HttpResponse httpResponse = HttpRequest.get(apiUrl)
                    .addHeaders(buildUSBrowserHeaders(true))
                    .timeout(10000)
                    .execute()) {

                if (!httpResponse.isOk()) {
                    log.warn("Fulfillment API non-200. status={}, body={}",
                            httpResponse.getStatus(), httpResponse.body());
                    if (httpResponse.getStatus() == 541) {
                        log.info("疑似被 WAF/CDN 拦截(541)。建议：降低频率、保持美国出口IP、完整浏览器请求头并先预热页面。");
                    }
                    return;
                }
                responseJsonObject = JSONObject.parseObject(httpResponse.body());
            }

            // 4) 解析 JSON（保留你原有逻辑）
            JSONObject pickupMessage = responseJsonObject.getJSONObject("body").getJSONObject("content").getJSONObject("pickupMessage");

            JSONArray stores = pickupMessage.getJSONArray("stores");

            if (stores == null) {
                log.info("您可能填错产品代码了，目前仅支持监控中国和日本地区的产品，注意不同国家的机型型号不同，下面是是错误信息");
                log.debug(pickupMessage.toString());
                return;
            }

            if (stores.isEmpty()) {
                log.info("您所在的 {} 附近没有Apple直营店，请检查您的地址是否正确", CONFIG.getAppleTaskConfig().getLocation());
            }

            stores.stream().filter(store -> {
                if (deviceItem.getStoreWhiteList().isEmpty()) {
                    return true;
                } else {
                    return filterStore((JSONObject) store, deviceItem);
                }
            }).forEach(k -> {

                JSONObject storeJson = (JSONObject) k;

                JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");

                String storeNames = storeJson.getString("storeName").trim();
                String deviceName = partsAvailability.getJSONObject(deviceItem.getDeviceCode()).getJSONObject("messageTypes").getJSONObject("regular").getString("storePickupProductTitle");
                String productStatus = partsAvailability.getJSONObject(deviceItem.getDeviceCode()).getString("pickupSearchQuote");


                String strTemp = "门店:{},型号:{},状态:{}";

                String content = StrUtil.format(strTemp, storeNames, deviceName, productStatus);

                if (judgingStoreInventory(storeJson, deviceItem.getDeviceCode())) {
                    JSONObject retailStore = storeJson.getJSONObject("retailStore");
                    content += buildPickupInformation(retailStore);
                    log.info(content);

                    pushAll(content, deviceItem.getPushConfigs());
                }
                log.info(content);
            });

        } catch (Exception e) {
            log.error("AppleMonitor error", e);
        }

    }


    /**
     * check store inventory
     *
     * @param storeJson   store json
     * @param productCode product code
     * @return boolean
     */
    private boolean judgingStoreInventory(JSONObject storeJson, String productCode) {

        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");
        String status = partsAvailability.getJSONObject(productCode).getString("pickupDisplay");
        return "available".equals(status);

    }

    /**
     * build pickup information
     *
     * @param retailStore retailStore
     * @return pickup message
     */
    private String buildPickupInformation(JSONObject retailStore) {
        String distanceWithUnit = retailStore.getString("distanceWithUnit");
        String twoLineAddress = retailStore.getJSONObject("address").getString("twoLineAddress");
        if (StrUtil.isEmpty(twoLineAddress)) {
            twoLineAddress = "暂无取货地址";
        }

        String daytimePhone = retailStore.getJSONObject("address").getString("daytimePhone");
        if (StrUtil.isEmpty(daytimePhone)) {
            daytimePhone = "暂无联系电话";
        }

        String lo = CONFIG.getAppleTaskConfig().getLocation();
        String messageTemplate = "\n取货地址:{},电话:{},距离{}:{}";
        return StrUtil.format(messageTemplate, twoLineAddress.replace("\n", " "), daytimePhone, lo, distanceWithUnit);
    }

    private boolean filterStore(JSONObject storeInfo, DeviceItem deviceItem) {
        String storeName = storeInfo.getString("storeName");
        return deviceItem.getStoreWhiteList().stream().anyMatch(k -> storeName.contains(k) || k.contains(storeName));
    }

    /**
     * build request headers
     *
     * @param baseCountryUrl base country url
     * @param productCode    product code
     * @return headers
     */
    private Map<String, List<String>> buildHeaders(String baseCountryUrl, String productCode) {

        ArrayList<String> referer = new ArrayList<>();
        referer.add(baseCountryUrl + "/shop/buy-iphone/iphone-14-pro/" + productCode);

        Map<String, List<String>> headers = new HashMap<>(10);
        headers.put(Header.REFERER.getValue(), referer);

        return headers;
    }

    /**
     * 生成“美国站”浏览器式请求头
     * isApi=true：Accept/Referer/Origin 走 XHR 风格；避免 br
     */
    private Map<String, String> buildUSBrowserHeaders(boolean isApi) {
        Map<String, String> h = new HashMap<>(12);
        h.put(Header.USER_AGENT.getValue(),
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/140.0.0.0 Safari/537.36");
        h.put("Connection", "keep-alive");
        h.put(Header.ACCEPT_LANGUAGE.getValue(), "en-US,en;q=0.9");
        // Hutool 默认不解 brotli，避免 br
        h.put(Header.ACCEPT_ENCODING.getValue(), "gzip, deflate");

        if (isApi) {
            h.put(Header.ACCEPT.getValue(), "application/json, text/plain, */*");
            h.put(Header.REFERER.getValue(), "https://www.apple.com/shop/buy-iphone");
            h.put("Origin", "https://www.apple.com");
            h.put("cookie", "dssid2=b198ed49-8a1e-4c8d-a5e4-8d28450aa7e7; dssf=1; as_sfa=Mnx1c3x1c3x8ZW5fVVN8Y29uc3VtZXJ8aW50ZXJuZXR8MHwwfDE; pxro=1; ac_ss=0e201d:0:1773125776; as_uct=0; as_dc=ucp4; sh_spksy=.; shld_bt_ck=lU9CW5MhEooHuiY-nUTy4w|1758595674|ELxNsiN_zRNLB1a3o70hLcXQzRknh2Zty0KW0-qGKI28-axgfU5WRp8gaJT1QbquHwijsbiZU2cV8iTsoDW4tHwUd917boJt9r8xfhKeQDOFeTQgmajFpSrxZHcUw16AKwbBfziNWhNllzRhw81jL8PFcCyEWH4QvYjvtwEUv_iaP_h7eiZqxl976rkrKKkL1Pyh41YCotrS8phVHfcOzwaft4OldDEH7XD6ChlVpl63us8uPLk4Nr0MteZRJK4PprxemlhVLy5R6ygvfWgRND8J_sjk54hKXpkuVSzyAWSkciOPdc2her_kcV0aHp02Wtf11ab9TamSnt6P0Er3jw|-P9BsqzEo8paIabQu0ajaotarQ0; as_pcts=abd8SoLKkngLNnSex-o6fyxAsk+ej+rqVfXRnPSfv:4NYOLIR+mOYU_KitVrN4mkUiQNyl3-DrAnHm-v90E2YU7XL1HaJ:1n5GeQMbXRSlMKWQJX4uuP8sdp4hD9+nsYWkLCL17r8014QRyOZeIBHT; as_gloc=8dcb102b952a17c3175b98513fe6cb6df081ee21a8a6b82c0c1c3af0452219c58853d7533a0c9e4019b40a8fa946508a0a5e7573dd0f2c756c5afb5cc5acfa02a9de38b5d890411c75000ee1cfa94e7d689c3293727cd644e4f38b4cf1713d50; geo=US; as_rumid=bc3b045c-4135-4b02-a726-69775b92ad77; s_fid=58FE5A5361C31864-3283B43103DBB6BD; s_cc=true; s_vi=[CS]v1|3468FBD29D6BAF90-60000C16942EF05B[CE]; s_sq=applestoreww%3D%2526c.%2526a.%2526activitymap.%2526page%253DAOS%25253A%252520home%25252Fshop_iphone%2526link%253Dall%252520models.take%252520your%252520pick.newiphone17%252520%252528inner%252520text%252529%252520%25257C%252520no%252520href%252520%25257C%252520body%2526region%253Dbody%2526pageIDType%253D1%2526.activitymap%2526.a%2526.c; shld_bt_m=KZWqXwvaM0ILY82LD8Fg6w|1758598090|X5T1V5H0N4ULhMxZ3WfC_g|6u0Jx2uYYotBqaIUUigNYoZYIkE; pt-dm=v1~x~rh5n3n6i~m~2");
            // 可选增强（部分 WAF 会参考这些）：
            // h.put("Sec-Fetch-Site", "same-origin");
            // h.put("Sec-Fetch-Mode", "cors");
            // h.put("Sec-Fetch-Dest", "empty");
        } else {
            h.put(Header.ACCEPT.getValue(),
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        }
        return h;
    }
}

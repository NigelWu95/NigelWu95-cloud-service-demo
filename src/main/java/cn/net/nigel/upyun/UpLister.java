package cn.net.nigel.upyun;

import cn.net.nigel.ILister;
import cn.net.nigel.common.SuitsException;
import com.upyun.UpAPIException;
import com.upyun.UpException;

import java.io.*;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class UpLister implements ILister<FolderItem> {

    private UpYunClient upYunClient;
    private String bucket;
    private String prefix;
    private String marker;
    private String endPrefix;
    private int limit;
    private boolean straight;
    private List<FolderItem> folderItems;

    public UpLister(UpYunClient upYunClient, String bucket, String prefix, String marker, String endPrefix,
                    int limit) throws SuitsException {
        this.upYunClient = upYunClient;
        this.bucket = bucket;
        this.prefix = prefix;
        this.marker = marker;
        this.endPrefix = endPrefix;
        this.limit = limit;
        doList();
    }

    @Override
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public void setMarker(String marker) {
        this.marker = marker == null ? "" : marker;
    }

    @Override
    public String getMarker() {
        return marker;
    }

    @Override
    public void setEndPrefix(String endPrefix) {
        this.endPrefix = endPrefix;
        checkedListWithEnd();
    }

    @Override
    public String getEndPrefix() {
        return endPrefix;
    }

    @Override
    public void setDelimiter(String delimiter) {}

    @Override
    public String getDelimiter() {
        return null;
    }

    @Override
    public void setLimit(int limit) {
        this.limit = limit;
    }

    @Override
    public int getLimit() {
        return limit;
    }

    @Override
    public void setStraight(boolean straight) {
        this.straight = straight;
    }

    @Override
    public boolean getStraight() {
        return straight;
    }

    @Override
    public boolean canStraight() {
        return straight || !hasNext() || (endPrefix != null && !"".equals(endPrefix));
    }

    private List<FolderItem> getListResult(String prefix, String marker, int limit) throws IOException, UpException {
        HttpURLConnection conn = upYunClient.listFilesConnection(bucket, prefix, marker, limit);
        StringBuilder text = new StringBuilder();
        int code = conn.getResponseCode();
        List<FolderItem> folderItems = new ArrayList<>();
//        is = conn.getInputStream(); // 状态码错误时不能使用 getInputStream()
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        InputStreamReader sr = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(sr);
        char[] chars = new char[4096];
        int length;
        while ((length = br.read(chars)) != -1) {
            text.append(chars, 0, length);
        }

        this.marker = conn.getHeaderField("x-upyun-list-iter");
        if ("g2gCZAAEbmV4dGQAA2VvZg".equals(this.marker) || text.length() == 0) this.marker = null;
        try {
            conn.disconnect();
            br.close();
            sr.close();
            is.close();
        } catch (IOException e) {
            br = null;
            sr = null;
            is = null;
        }
        if (code >= 400) throw new UpAPIException(code, text.toString());
        String result = text.toString();
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.indexOf("\t") > 0) {
                folderItems.add(new FolderItem(line));
            }
        }
        return folderItems;
    }

    private void checkedListWithEnd() {
        String endKey = currentEndKey();
        // 删除大于 endPrefix 的元素，如果 endKey 大于等于 endPrefix 则需要进行筛选且使得 marker = null
        if (endPrefix != null && !"".equals(endPrefix) && endKey != null && endKey.compareTo(endPrefix) >= 0) {
            marker = null;
            int size = folderItems.size();
            // SDK 中返回的是 ArrayList，使用 remove 操作性能一般较差，同时也为了避免 Collectors.toList() 的频繁 new 操作，根据返
            // 回的 list 为文件名有序的特性，直接从 end 的位置进行截断
            for (int i = 0; i < size; i++) {
                if (folderItems.get(i).key.compareTo(endPrefix) > 0) {
                    folderItems = folderItems.subList(0, i);
                    return;
                }
            }
        }
    }

    private void doList() throws SuitsException {
        try {
            folderItems = getListResult(prefix, marker, limit);
            checkedListWithEnd();
        } catch (UpAPIException e) {
            throw new SuitsException(e.statusCode, e.getMessage());
        } catch (NullPointerException e) {
            throw new SuitsException(400000, "lister maybe already closed, " + e.getMessage());
        } catch (Exception e) {
            throw new SuitsException(-1, "failed, " + e.getMessage());
        }
    }

    @Override
    public void listForward() throws SuitsException {
        if (hasNext()) {
            doList();
        } else {
            folderItems.clear();
        }
    }

    @Override
    public boolean hasNext() {
        return marker != null && !"".equals(marker);
    }

    @Override
    public boolean hasFutureNext() throws SuitsException {
        int times = 50000 / (folderItems.size() + 1);
        times = times > 10 ? 10 : times;
        List<FolderItem> futureList = folderItems;
        while (hasNext() && times > 0 && futureList.size() < 10001) {
            times--;
            doList();
            futureList.addAll(folderItems);
        }
        folderItems = futureList;
        return hasNext();
    }

    @Override
    public List<FolderItem> currents() {
        return folderItems;
    }

    @Override
    public FolderItem currentLast() {
        return folderItems.size() > 0 ? folderItems.get(folderItems.size() - 1) : null;
    }

    @Override
    public String currentEndKey() {
        if (hasNext()) {
            Base64.Decoder decoder = Base64.getDecoder();
            String keyString = new String(decoder.decode(marker));
            return keyString.substring(bucket.length() + 3);
        }
        FolderItem last = currentLast();
        return last != null ? last.key : null;
    }

    @Override
    public void updateMarkerBy(FolderItem object) {
        if (object != null) {
            Base64.Encoder encoder = Base64.getEncoder();
            marker = new String(encoder.encode((bucket + "/@" + object.key).getBytes()));
        }
    }

    @Override
    public void close() {
        upYunClient = null;
        folderItems = null;
    }
}

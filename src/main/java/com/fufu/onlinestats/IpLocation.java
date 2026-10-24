package com.fufu.onlinestats;

/**
 * Full result of one IP location lookup. Field names follow the upstream JSON so the
 * console can show everything the provider returns without extra mapping.
 */
public record IpLocation(String ip,
                         String dec,
                         String country,
                         String countryCode,
                         String province,
                         String city,
                         String districts,
                         String isp,
                         String net,
                         String idc,
                         String zipcode,
                         String areacode,
                         String protocol,
                         String location,
                         long resolvedAt,
                         String error) {

    public boolean resolved() {
        return location != null || province != null || city != null;
    }

    public static IpLocation local(String ip) {
        return new IpLocation(ip, null, "本地网络", null, null, null, null, null,
            null, null, null, null, null, "本地网络", System.currentTimeMillis(), null);
    }

    public static IpLocation failed(String ip, String error) {
        return new IpLocation(ip, null, null, null, null, null, null, null,
            null, null, null, null, null, null, System.currentTimeMillis(), error);
    }
}

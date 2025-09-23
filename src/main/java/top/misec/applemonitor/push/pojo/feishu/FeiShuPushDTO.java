package top.misec.applemonitor.push.pojo.feishu;

import lombok.Builder;
import lombok.Data;

/**
 * Bark推送DTO
 *
 * @author moshi
 */
@Data
@Builder
public class FeiShuPushDTO {
    private String text;
    private String botWebHooks;
    private String secret;

}

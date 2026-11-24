package com.iflytek.astron.console.toolkit.service.repo;

import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepoServiceVolcengineIdTest {

    @ParameterizedTest
    @CsvSource({
            "lumin_viking, lumin_viking",
            "'  lumin_viking  ', lumin_viking",
            "kb-8349ef57441ab57, kb-8349ef57441ab57",
            "default/lumin_viking, default/lumin_viking",
            "' my-proj / lumin ', my-proj/lumin"
    })
    void acceptsNameResourceIdAndProjectName(String raw, String expected) {
        assertEquals(expected, RepoService.normalizeVolcengineRepoId(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "1abc", "lumin viking", "a/b/c", "/lumin", "proj/", "kb-", "名字"})
    void rejectsInvalidIdentifiers(String raw) {
        BusinessException e = assertThrows(BusinessException.class, () -> RepoService.normalizeVolcengineRepoId(raw));
        assertEquals(ResponseEnum.REPO_VOLCENGINE_BIND_FAILED, e.getResponseEnum());
    }
}

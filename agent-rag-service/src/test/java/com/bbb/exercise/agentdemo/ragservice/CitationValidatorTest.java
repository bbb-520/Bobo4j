package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.ragservice.answer.CitationValidator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证引用必须来自真实证据，并正确去重和映射来源。 */
class CitationValidatorTest {
    @Test
    void inventedSourcesAreRejected() {
        assertThatThrownBy(() -> CitationValidator.validate("Budget is97 [S9]", 2))
                .hasMessage("CITATION_INVALID");
    }

    @Test
    void missingCitationCannotPassGrounding() {
        assertThatThrownBy(() -> CitationValidator.validate("Budget is97.", 2))
                .hasMessage("CITATION_MISSING");
    }

    @Test
    void realIdsAreDeduplicatedAndMapped() {
        assertThat(CitationValidator.validate("预算为97 [S2]。结论 [S2] [S1]", 2)).containsExactly(2, 1);
    }

    @Test
    void eachBulletClaimNeedsItsOwnCitation() {
        assertThatThrownBy(() -> CitationValidator.validate(
                "- 技术栈为 Java、Spring Boot 和 MySQL。[S1]\n- 项目使用 Redis 缓存。", 1))
                .hasMessage("UNCITED_CLAIM");
    }

    @Test
    void aCitedTableRowCannotCoverAnotherUncitedRow() {
        assertThatThrownBy(() -> CitationValidator.validate(
                "| 技术 | 用途 |\n| --- | --- |\n| Java | 服务开发 [S1] |\n| Redis | 缓存 |", 1))
                .hasMessage("UNCITED_CLAIM");
    }

    @Test
    void citedBulletItemsAndTableRowsRemainValid() {
        assertThat(CitationValidator.validate(
                "- Java 开发。[S1]\n- Redis 缓存。[S2]\n\n| 项目 | 技术 |\n| --- | --- |\n| 服务 | Java [S1] |", 2))
                .containsExactly(1, 2);
    }

    @Test
    void shortSectionLabelCanIntroduceCitedItemsWithoutBeingTreatedAsAClaim() {
        assertThat(CitationValidator.validate("技术技能：\n- Java 开发。[S1]\n- Redis 缓存。[S1]", 1))
                .containsExactly(1);
    }

    @Test
    void singleColumnTableStillRequiresACitationOnEveryDataRow() {
        assertThatThrownBy(() -> CitationValidator.validate(
                "| 技术 |\n| --- |\n| Java [S1] |\n| Redis |", 1))
                .hasMessage("UNCITED_CLAIM");
    }

    @Test
    void horizontalRuleCannotHideAnUncitedClaimAsATableHeading() {
        assertThatThrownBy(() -> CitationValidator.validate(
                "这是一个没有来源支持的完整事实段落，包含未经核实的项目业绩和技术结论。\n---\n\n已核实内容。[S1]", 1))
                .hasMessage("UNCITED_CLAIM");
    }
}

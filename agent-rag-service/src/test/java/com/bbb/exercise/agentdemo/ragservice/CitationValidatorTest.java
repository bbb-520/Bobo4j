package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.answer.CitationValidator;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
class CitationValidatorTest {
 @Test void inventedSourcesAreRejected(){assertThatThrownBy(()->CitationValidator.validate("Budget is97 [S9]",2)).hasMessage("CITATION_INVALID");}
 @Test void missingCitationCannotPassGrounding(){assertThatThrownBy(()->CitationValidator.validate("Budget is97.",2)).hasMessage("CITATION_MISSING");}
 @Test void realIdsAreDeduplicatedAndMapped(){assertThat(CitationValidator.validate("预算为97 [S2]。结论 [S2] [S1]",2)).containsExactly(2,1);}
}

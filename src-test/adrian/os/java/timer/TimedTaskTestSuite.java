package adrian.os.java.timer;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;


/**
 *
 */
@Suite
@SelectClasses({ TimedTaskTest.class, FutureTimedTaskTest.class, BugReproductionTest.class })
public class TimedTaskTestSuite {

}

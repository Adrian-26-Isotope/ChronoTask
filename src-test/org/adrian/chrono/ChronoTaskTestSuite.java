package org.adrian.chrono;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;


/**
 *
 */
@Suite
@SelectClasses({ ChronoTaskTest.class, FutureChronoTaskTest.class, SystemTest.class })
public class ChronoTaskTestSuite {

}

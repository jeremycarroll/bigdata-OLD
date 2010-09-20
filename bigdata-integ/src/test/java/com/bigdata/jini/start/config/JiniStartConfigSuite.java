/***********************************************************************
 *
 * $Id$
 *
 * Copyright (c) 2000-2010 Nokia Corporation.
 *
 * This material, including documentation and any related computer
 * programs, is protected by copyright controlled by Nokia Corporation.
 * All rights are reserved.  Copying, including reproducing, storing,
 * adapting or translating, any or all of this material requires the prior
 * written consent of Nokia Corporation.  This material also contains
 * confidential information which may not be disclosed to others without
 * the prior written consent of Nokia Corporation.
 **********************************************************************/
package com.bigdata.jini.start.config;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * @author blevine
 *
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    TestServiceConfiguration.class,
    TestServiceConfigurationRemote.class,
    TestZookeeperServerEntry.class
})

public class JiniStartConfigSuite
{
}

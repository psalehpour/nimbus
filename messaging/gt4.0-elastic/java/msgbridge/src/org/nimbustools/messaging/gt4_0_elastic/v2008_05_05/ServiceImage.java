/*
 * Copyright 1999-2008 University of Chicago
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.nimbustools.messaging.gt4_0_elastic.v2008_05_05;

import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.RegisterImageResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.RegisterImageType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DeregisterImageResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DeregisterImageType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DescribeImagesResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DescribeImagesType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ModifyImageAttributeResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ModifyImageAttributeType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ResetImageAttributeResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ResetImageAttributeType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DescribeImageAttributeResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.DescribeImageAttributeType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ConfirmProductInstanceResponseType;
import org.nimbustools.messaging.gt4_0_elastic.generated.v2008_05_05.ConfirmProductInstanceType;

import java.rmi.RemoteException;

public interface ServiceImage {
    
    // -------------------------------------------------------------------------
    // IMAGE RELATED
    // -------------------------------------------------------------------------

    public RegisterImageResponseType registerImage(
                        RegisterImageType registerImageRequestMsg)
            throws RemoteException;

    public DeregisterImageResponseType deregisterImage(
                        DeregisterImageType deregisterImageRequestMsg)
            throws RemoteException;

    public DescribeImagesResponseType describeImages(
                        DescribeImagesType describeImagesRequestMsg)
            throws RemoteException;

    public ModifyImageAttributeResponseType modifyImageAttribute(
                        ModifyImageAttributeType modifyImageAttributeRequestMsg)
            throws RemoteException;

    public ResetImageAttributeResponseType resetImageAttribute(
                        ResetImageAttributeType resetImageAttributeRequestMsg)
            throws RemoteException;

    public DescribeImageAttributeResponseType describeImageAttribute(
                        DescribeImageAttributeType describeImageAttributeRequestMsg)
            throws RemoteException;

    public ConfirmProductInstanceResponseType confirmProductInstance(
                        ConfirmProductInstanceType confirmProductInstanceRequestMsg)
            throws RemoteException;
}
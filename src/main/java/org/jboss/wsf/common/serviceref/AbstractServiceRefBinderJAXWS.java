/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.wsf.common.serviceref;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.jws.HandlerChain;
import javax.naming.Referenceable;
import javax.xml.namespace.QName;
import javax.xml.ws.RespectBinding;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceClient;
import javax.xml.ws.WebServiceRef;
import javax.xml.ws.WebServiceRefs;
import javax.xml.ws.soap.Addressing;
import javax.xml.ws.soap.MTOM;

import org.jboss.wsf.spi.WSFException;
import org.jboss.wsf.spi.metadata.j2ee.serviceref.UnifiedServiceRefMetaData;
import org.jboss.wsf.spi.serviceref.ServiceRefBinder;

/**
 * Binds a JAXWS service object factory to the client's ENC.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class AbstractServiceRefBinderJAXWS extends AbstractServiceRefBinder
{
   public final Referenceable createReferenceable(final UnifiedServiceRefMetaData serviceRef, final ClassLoader loader)
   {
      WebServiceRef wsref = null;

      if (null == loader)
         throw new IllegalArgumentException("There needs to be a classloader available");

      // Build the list of @WebServiceRef relevant annotations
      List<WebServiceRef> wsrefList = new ArrayList<WebServiceRef>();
      Addressing addressingAnnotation = null;
      MTOM mtomAnnotation = null;
      RespectBinding respectBindingAnnotation = null;

      AnnotatedElement anElement = (AnnotatedElement) serviceRef.getAnnotatedElement();
      if (anElement != null)
      {
         for (Annotation an : anElement.getAnnotations())
         {
            if (an instanceof Addressing)
            {
               addressingAnnotation = (Addressing) an;
               continue;
            }

            if (an instanceof MTOM)
            {
               mtomAnnotation = (MTOM) an;
               continue;
            }

            if (an instanceof RespectBinding)
            {
               respectBindingAnnotation = (RespectBinding) an;
               continue;
            }

            if (an instanceof WebServiceRef)
            {
               wsrefList.add((WebServiceRef) an);
               continue;
            }

            if (an instanceof WebServiceRefs)
            {
               WebServiceRefs wsrefs = (WebServiceRefs) an;
               for (WebServiceRef aux : wsrefs.value())
                  wsrefList.add(aux);
            }
         }
      }

      // Use the single @WebServiceRef
      if (wsrefList.size() == 1)
      {
         wsref = wsrefList.get(0);
      }
      else
      {
         for (WebServiceRef aux : wsrefList)
         {
            if (serviceRef.getServiceRefName().endsWith("/" + aux.name()))
            {
               wsref = aux;
               break;
            }
         }
      }

      Class<?> targetClass = null;
      if (anElement instanceof Field)
      {
         targetClass = ((Field) anElement).getType();
      }
      else if (anElement instanceof Method)
      {
         targetClass = ((Method) anElement).getParameterTypes()[0];
      }
      else
      {
         if (wsref != null && (wsref.type() != Object.class))
            targetClass = wsref.type();
      }

      String targetClassName = (targetClass != null ? targetClass.getName() : null);

      String serviceImplClass = null;

      // #1 Use the explicit @WebServiceRef.value
      if (wsref != null && wsref.value() != Service.class)
         serviceImplClass = wsref.value().getName();

      // #2 Use the target ref type
      if (serviceImplClass == null && targetClass != null && Service.class.isAssignableFrom(targetClass))
         serviceImplClass = targetClass.getName();

      // #3 Use <service-interface>
      if (serviceImplClass == null && serviceRef.getServiceInterface() != null)
         serviceImplClass = serviceRef.getServiceInterface();

      // #4 Use javax.xml.ws.Service
      if (serviceImplClass == null)
         serviceImplClass = Service.class.getName();

      // #1 Use the explicit @WebServiceRef.type
      if (wsref != null && wsref.type() != Object.class)
         targetClassName = wsref.type().getName();

      // #2 Use the target ref type
      if (targetClassName == null && targetClass != null && Service.class.isAssignableFrom(targetClass) == false)
         targetClassName = targetClass.getName();

      // Set the wsdlLocation if there is no override already
      if (serviceRef.getWsdlOverride() == null && wsref != null && wsref.wsdlLocation().length() > 0)
         serviceRef.setWsdlOverride(wsref.wsdlLocation());

      // Set the handlerChain from @HandlerChain on the annotated element
      String handlerChain = serviceRef.getHandlerChain();
      if (anElement != null)
      {
         HandlerChain anHandlerChain = anElement.getAnnotation(HandlerChain.class);
         if (handlerChain == null && anHandlerChain != null && anHandlerChain.file().length() > 0)
            handlerChain = anHandlerChain.file();
      }

      // Resolve path to handler chain
      if (handlerChain != null)
      {
         try
         {
            new URL(handlerChain);
         }
         catch (MalformedURLException ex)
         {
            Class<?> declaringClass = null;
            if (anElement instanceof Field)
               declaringClass = ((Field) anElement).getDeclaringClass();
            else if (anElement instanceof Method)
               declaringClass = ((Method) anElement).getDeclaringClass();
            else if (anElement instanceof Class)
               declaringClass = (Class<?>) anElement;

            handlerChain = declaringClass.getPackage().getName().replace('.', '/') + "/" + handlerChain;
         }

         serviceRef.setHandlerChain(handlerChain);
      }

      // Extract service QName for target service
      if (null == serviceRef.getServiceQName())
      {
         try
         {
            Class<?> serviceClass = loader.loadClass(serviceImplClass);
            if (serviceClass.getAnnotation(WebServiceClient.class) != null)
            {
               WebServiceClient clientDecl = (WebServiceClient) serviceClass.getAnnotation(WebServiceClient.class);
               serviceRef.setServiceQName(new QName(clientDecl.targetNamespace(), clientDecl.name()));
               //use the @WebServiceClien(wsdlLocation=...) if the service ref wsdl location returned at this time would be null
               if (clientDecl.wsdlLocation().length() > 0 && serviceRef.getWsdlLocation() == null)
               {
                  serviceRef.setWsdlOverride(clientDecl.wsdlLocation());
               }
            }
         }
         catch (ClassNotFoundException e)
         {
            WSFException.rethrow("Cannot extract service QName for target service", e);
         }
      }

      if (addressingAnnotation != null)
      {
         serviceRef.setAddressingEnabled(addressingAnnotation.enabled());
         serviceRef.setAddressingRequired(addressingAnnotation.required());
         serviceRef.setAddressingResponses(addressingAnnotation.responses().toString());
      }

      if (mtomAnnotation != null)
      {
         serviceRef.setMtomEnabled(mtomAnnotation.enabled());
         serviceRef.setMtomThreshold(mtomAnnotation.threshold());
      }

      if (respectBindingAnnotation != null)
      {
         serviceRef.setRespectBindingEnabled(respectBindingAnnotation.enabled());
      }

      return this.createJAXWSReferenceable(serviceImplClass, targetClassName, serviceRef);
   }

   /**
    * Template method for creating stack specific JAXWS referenceables.
    * 
    * @param serviceImplClass service implementation class name
    * @param targetClassName target class name
    * @param serviceRef service reference UMDM
    * @return stack specific JAXWS JNDI referenceable
    */
   protected abstract Referenceable createJAXWSReferenceable(final String serviceImplClass,
         final String targetClassName, final UnifiedServiceRefMetaData serviceRef);
}
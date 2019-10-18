/*
 * Copyright 2019
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.revieweditor;

import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectAnnotationByAddr;
import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectFsByAddr;
import static de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaBehavior.visibleWhen;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.wicket.event.Broadcast;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.CasProvider;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.adapter.TypeAdapter;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.AnnotatorState;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.FeatureState;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.LinkWithRoleModel;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.VID;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxLink;

public class SpanAnnotationPanel 
    extends Panel
{
    private static final long serialVersionUID = 7375798934091777439L;
    
    private final Logger LOG = LoggerFactory.getLogger(getClass());
    
    private @SpringBean AnnotationSchemaService annotationService;
    private @SpringBean FeatureSupportRegistry featureSupportRegistry;
    
    private static final String CID_TEXT = "text";
    private static final String CID_FEATURES_CONTAINER = "featuresContainer";
    private static final String CID_TEXT_FEATURES = "textFeatures";
    private static final String CID_FEATURES = "features";
    private static final String CID_OPEN = "open";
    private static final String CID_OPENED = "opened";
    private static final String CID_LABEL = "label";
    private static final String CID_VALUE = "value";
    private static final String CID_PRE_CONTEXT = "preContext";
    private static final String CID_POST_CONTEXT = "postContext";
    
    private final CasProvider casProvider;
    private final AnnotatorState state;
    private WebMarkupContainer featuresContainer;
    
    public SpanAnnotationPanel(String aId, IModel<LinkWithRoleModel> aModel,
        CasProvider aCasProvider, AnnotatorState aState)
    {
        super(aId, aModel);
        casProvider = aCasProvider;
        state = aState;
    
        LinkWithRoleModel link = aModel.getObject();
    
        try {
            CAS cas = casProvider.get();
            FeatureStructure fs = selectFsByAddr(cas, link.targetAddr);
            VID vid = new VID(fs);
            AnnotationLayer layer = annotationService.findLayer(state.getProject(), fs);
            AnnotationFS aFS = selectAnnotationByAddr(cas, link.targetAddr);
            int begin = aFS.getBegin();
            int end = aFS.getEnd();
            
            List<FeatureState> features = listFeatures(fs, layer, vid);
            List<FeatureState> textFeatures = features.stream()
                .filter(state -> state.feature.getType().equals("uima.cas.String") 
                    && state.feature.getTagset() == null)
                .collect(Collectors.toList());
            features.removeAll(textFeatures);
    
            LambdaAjaxLink openButton = new LambdaAjaxLink(CID_OPEN, _target -> {
                send(this, Broadcast.BUBBLE,
                    new SelectAnnotationEvent(vid, begin, end, _target));
            });
            openButton.add(visibleWhen(() 
                -> !state.getSelection().getAnnotation().equals(vid)));
    
            LambdaAjaxLink openedButton = new LambdaAjaxLink(CID_OPENED, _target -> {
                send(this, Broadcast.BUBBLE,
                    new SelectAnnotationEvent(vid, begin, end, _target));
            });
            openedButton.add(visibleWhen(()
                -> state.getSelection().getAnnotation().equals(vid)));
    
            String text = cas.getDocumentText();
            int windowSize = 50;
            int contextBegin = aFS.getBegin() < windowSize
                ? 0 : aFS.getBegin() - windowSize;
            int contextEnd = aFS.getEnd() + windowSize > text.length()
                ? text.length() : aFS.getEnd() + windowSize;
            String preContext = text.substring(contextBegin, aFS.getBegin());
            String postContext = text.substring(aFS.getEnd(), contextEnd);
    
            featuresContainer = new WebMarkupContainer(CID_FEATURES_CONTAINER);
            featuresContainer.setOutputMarkupId(true);
            featuresContainer.add(createTextFeaturesList(textFeatures));
            featuresContainer.add(createFeaturesList(features));
            featuresContainer.add(new Label(CID_PRE_CONTEXT, preContext));
            featuresContainer.add(new Label(CID_TEXT, link.label));
            featuresContainer.add(new Label(CID_POST_CONTEXT, postContext));
            featuresContainer.add(openButton);
            featuresContainer.add(openedButton);
            
            add(featuresContainer);
        }
        catch (IOException e) {
            LOG.error("Unable to load CAS", e);
        }
    }
    
    private List<FeatureState> listFeatures(FeatureStructure aFs,
        AnnotationLayer aLayer, VID aVid)
    {
        
        TypeAdapter adapter = annotationService.getAdapter(aLayer);
    
        // Populate from feature structure
        List<FeatureState> featureStates = new ArrayList<>();
        for (AnnotationFeature feature : annotationService.listAnnotationFeature(aLayer)) {
            if (!feature.isEnabled()) {
                continue;
            }
        
            Serializable value = null;
            if (aFs != null) {
                value = adapter.getFeatureValue(feature, aFs);
            }
        
            FeatureState featureState = new FeatureState(aVid, feature, value);
            featureStates.add(featureState);
            featureState.tagset = annotationService.listTags(featureState.feature.getTagset());
        }
    
        return featureStates;
    }
    
    private ListView<FeatureState> createTextFeaturesList(List<FeatureState> features)
    {
        return new ListView<FeatureState>(CID_TEXT_FEATURES, features)
        {
            private static final long serialVersionUID = 2518085396361327922L;
            
            @Override
            protected void populateItem(ListItem<FeatureState> item)
            {
                populateFeatureItem(item);
            }
        };
    }
    
    private ListView<FeatureState> createFeaturesList(List<FeatureState> features)
    {
        return new ListView<FeatureState>(CID_FEATURES, features)
        {
            private static final long serialVersionUID = 16641722427333232L;
            
            @Override
            protected void populateItem(ListItem<FeatureState> item)
            {
                populateFeatureItem(item);
            }
        };
    }
    
    private void populateFeatureItem(ListItem<FeatureState> item) {
        // Feature editors that allow multiple values may want to update themselves,
        // e.g. to add another slot.
        item.setOutputMarkupId(true);
    
        final FeatureState featureState = item.getModelObject();
    
        Label label = new Label(CID_LABEL, featureState.feature.getUiName() + ": ");
        Label value = new Label(CID_VALUE, featureState.value);
    
        item.add(label);
        item.add(value);
    }
}

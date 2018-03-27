/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.ui.kb;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.event.Broadcast;
import org.apache.wicket.extensions.markup.html.repeater.tree.AbstractTree;
import org.apache.wicket.extensions.markup.html.repeater.tree.DefaultNestedTree;
import org.apache.wicket.extensions.markup.html.repeater.tree.ITreeProvider;
import org.apache.wicket.extensions.markup.html.repeater.tree.content.Folder;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.spring.injection.annot.SpringBean;

import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxFormSubmittingBehavior;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.RdfUtils;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxConceptSelectionEvent;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxNewConceptEvent;
import de.tudarmstadt.ukp.inception.ui.kb.util.WriteProtectionBehavior;

public class ConceptTreePanel extends Panel {
    private static final long serialVersionUID = -4032884234215283745L;

    private @SpringBean KnowledgeBaseService kbService;
    
    private IModel<KBHandle> selectedConcept;
    private IModel<KnowledgeBase> kbModel;
    private IModel<Preferences> preferences;
    
    public ConceptTreePanel(String aId, IModel<KnowledgeBase> aKbModel,
            IModel<KBHandle> selectedConceptModel) {
        super(aId, selectedConceptModel);
        
        setOutputMarkupId(true);
        
        selectedConcept = selectedConceptModel;
        kbModel = aKbModel;
        preferences = Model.of(new Preferences());
        
        AbstractTree<KBHandle> tree = new DefaultNestedTree<KBHandle>("tree",
                new ConceptTreeProvider(), Model.ofSet(new HashSet<>()))
        {
            private static final long serialVersionUID = -270550186750480253L;

            @Override
            protected Component newContentComponent(String id, IModel<KBHandle> node)
            {
                return new Folder<KBHandle>(id, this, node) {
                    private static final long serialVersionUID = -2007320226995118959L;

                    @Override
                    protected IModel<String> newLabelModel(IModel<KBHandle> aModel)
                    {
                        return Model.of(aModel.getObject().getUiLabel());
                    }
                    
                    @Override
                    protected boolean isClickable()
                    {
                        return true;
                    }

                    @Override
                    protected void onClick(AjaxRequestTarget aTarget)
                    {
                        if (selectedConcept.getObject() != null) {
                            selectedConcept.detach();
                            updateNode(selectedConcept.getObject(), aTarget);
                        }
                        selectedConcept.setObject(getModelObject());
                        updateNode(selectedConcept.getObject(), aTarget);
                        actionSelectionChanged(aTarget);
                    }

                    @Override
                    protected boolean isSelected()
                    {
                        return Objects.equals(getModelObject(), selectedConcept.getObject());
                    }                
                };
            }
        };
        add(tree);
        
        LambdaAjaxLink addLink = new LambdaAjaxLink("add", target -> send(getPage(),
                Broadcast.BREADTH, new AjaxNewConceptEvent(target)));
        addLink.add(new Label("label", new ResourceModel("concept.list.add")));
        addLink.add(new WriteProtectionBehavior(kbModel));
        add(addLink);

        Form<Preferences> form = new Form<>("form", CompoundPropertyModel.of(preferences));
        form.add(new CheckBox("showAllConcepts").add(
                new LambdaAjaxFormSubmittingBehavior("change", this::actionPreferenceChanged)));
        add(form);
    }
    
    private void actionSelectionChanged(AjaxRequestTarget aTarget) {
        // if the selection changes, publish an event denoting the change
        AjaxConceptSelectionEvent e = new AjaxConceptSelectionEvent(aTarget,
                selectedConcept.getObject());
        send(getPage(), Broadcast.BREADTH, e);
    }
    
    /**
     * If the user disabled "show all" but a concept from an implicit namespace was selected, the
     * concept selection is cancelled. In any other case this component is merely updated via AJAX.
     */
    private void actionPreferenceChanged(AjaxRequestTarget aTarget) {
        if (!preferences.getObject().showAllConcepts && selectedConcept.getObject() != null
                && RdfUtils.isFromImplicitNamespace(selectedConcept.getObject())) {
            send(getPage(), Broadcast.BREADTH, new AjaxConceptSelectionEvent(aTarget, null));
        } else {
            aTarget.add(this);
        }
    }
    
    private class ConceptTreeProvider implements ITreeProvider<KBHandle>
    {
        private static final long serialVersionUID = 5318498575532049499L;

        @Override
        public void detach()
        {
            // Nothing to do
        }

        @Override
        public Iterator<? extends KBHandle> getRoots()
        {
            return kbService
                    .listRootConcepts(kbModel.getObject(), preferences.getObject().showAllConcepts)
                    .iterator();
        }

        @Override
        public boolean hasChildren(KBHandle aNode)
        {
            // FIXME Instead of querying for the whole list of children here, it would probably
            // be more efficient to just query if at least one child exists.
            return !kbService.listChildConcepts(kbModel.getObject(), aNode.getIdentifier(),
                    preferences.getObject().showAllConcepts).isEmpty();
        }

        @Override
        public Iterator<? extends KBHandle> getChildren(KBHandle aNode)
        {
            return kbService.listChildConcepts(kbModel.getObject(), aNode.getIdentifier(),
                    preferences.getObject().showAllConcepts).iterator();
        }

        @Override
        public IModel<KBHandle> model(KBHandle aObject)
        {
            return Model.of(aObject);
        }
    }
    
    static class Preferences implements Serializable {
        private static final long serialVersionUID = 8310379405075949753L;

        boolean showAllConcepts;
    }
}
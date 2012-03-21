package com.temenos.interaction.core.link;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.odata4j.core.OEntities;
import org.odata4j.core.OEntity;
import org.odata4j.core.OLink;
import org.odata4j.core.OLinks;
import org.odata4j.edm.EdmEntitySet;
import org.odata4j.edm.EdmNavigationProperty;
import org.odata4j.format.xml.XmlFormatWriter;

import com.jayway.jaxrs.hateoas.HateoasContext;
import com.jayway.jaxrs.hateoas.LinkableInfo;
import com.temenos.interaction.core.dynaresource.HTTPDynaRIM;
import com.temenos.interaction.core.state.ResourceInteractionModel;

public class ResourceRegistry implements HateoasContext {

//	private EdmDataServices edmDataServices;
	// map of resource path to interaction model
	private Map<String, HTTPDynaRIM> rimMap = new HashMap<String, HTTPDynaRIM>();
	// map of entity name to resource path
	private Map<String, String> entityResourcePathMap = new HashMap<String, String>();
	// map of resource state to resource path
	private Map<ResourceState, String> statePathMap = new HashMap<ResourceState, String>();
	// map of link key to transition
	private Map<String, Transition> linkTransitionMap = new HashMap<String, Transition>();
	
	public ResourceRegistry() {}

	/**
	 * Construct and fill the resource registry from a single root resource.  In a RESTful interaction
	 * there should be no way to reach a resource unless it has a link from another resource.
	 * @param root
	 */
	public ResourceRegistry(HTTPDynaRIM root) {
		collectResources(root);
	}

	private void collectResources(ResourceInteractionModel resource) {
		// the registry can only be constructed with HTTPDynaRIM, no way to have children of any other type
		assert(resource instanceof HTTPDynaRIM);
		add((HTTPDynaRIM) resource);
		for (ResourceInteractionModel r : resource.getChildren()) {
			if (!rimMap.containsKey(r.getFQResourcePath())) {
				collectResources(r);
			}
		}
	}
	
	/**
	 * Construct and fill the resource registry with a set of resources.
	 * @param resources
	 */
	public ResourceRegistry(Set<HTTPDynaRIM> resources) {
		for (HTTPDynaRIM r : resources)
			add(r);
	}

	public void add(HTTPDynaRIM rim) {
		rimMap.put(rim.getFQResourcePath(), rim);
		
		// populate a map of resources and their paths, and resource states and their paths 
		if (rim.getStateMachine() != null) {
			ResourceStateMachine stateMachine = rim.getStateMachine();
			// do not update entity resource path if this resource is a child state
			if (stateMachine.getInitial().equals(rim.getCurrentState())) {
				entityResourcePathMap.put(rim.getEntityName(), rim.getFQResourcePath());
			}
			/*
			 *  create the state-path map for "self state" child states, not necessary to
			 *  populate the map with other resource states as these RIMs will be added
			 *  here also 
			 */
			Collection<ResourceState> resourceStates = rim.getCurrentState().getAllTargets();
			for (ResourceState childState : resourceStates) {
				if (childState.isSelfState()) {
					statePathMap.put(childState, rim.getFQResourcePath());
				} else if (!childState.getEntityName().equals(rim.getCurrentState().getEntityName())) {
					statePathMap.put(childState, childState.getPath());
				}
			}
		} else {
			// must be just an entity with no state machine
			entityResourcePathMap.put(rim.getEntityName(), rim.getFQResourcePath());
		}
		
		
		/* 
		 * test if current state of resource has been supplied, HTTPDynaRIM could be
		 * constructed without a state model, and this registry could be filled with
		 * a set of resources that have no state model
		 */
		if (rim.getCurrentState() != null) {
			for (ResourceState targetState : rim.getCurrentState().getAllTargets()) {
				// linkKey = the target entity name and state name
				String linkKey = targetState.getEntityName() + "." + targetState.getName();
				Transition transition = rim.getCurrentState().getTransition(targetState);
				linkTransitionMap.put(linkKey, transition);
			}
		}
	}
	
	
	public String getEntityResourcePath(String entityName) {
		return entityResourcePathMap.get(entityName);
	}
	
	public Set<ResourceInteractionModel> getResourceInteractionModels() {
		Set<ResourceInteractionModel> resources = new HashSet<ResourceInteractionModel>();
		for (String r : rimMap.keySet()) {
			resources.add(rimMap.get(r));
		}
		return resources;
	}

	/**
	 * Where we have an OEntity, the links are generated to other entities. We
	 * just need to re write this to point to our resources.
	 * 
	 * @param entity
	 * @param currentState
	 * @return
	 */
	public OEntity rebuildOEntityLinks(OEntity entity, ResourceState currentState) {
		// these are links to associated Entities
		List<OLink> associatedLinks = new ArrayList<OLink>();

		EdmEntitySet ees = entity.getEntitySet();
		for (EdmNavigationProperty np : ees.getType().getNavigationProperties()) {
			String otherEntity = np.getToRole().getType().getName();			
			
			// TODO get navigation value, and replace {id} with navigation property value
			// TODO add our relations
	        String rel = XmlFormatWriter.related + otherEntity;

	        
			String pathOtherResource = getEntityResourcePath(otherEntity);
			if (pathOtherResource != null) {
				associatedLinks.add(OLinks.relatedEntity(rel, otherEntity, pathOtherResource));
			}
		}
		
		// these are links supplied from the producer at runtime (not defined in the associations section of the EDMX file)
		for (OLink link : entity.getLinks()) {
			System.out.println("Do something: " + link.getHref());
			throw new RuntimeException();
		}

		// these are links or forms to transition to another state
		List<OLink> transitionLinks = new ArrayList<OLink>();
		if (currentState != null) {
			Collection<ResourceState> targetStates = currentState.getAllTargets();
			for (ResourceState s : targetStates) {
				TransitionCommandSpec cs = s.getTransition(s).getCommand();
				transitionLinks.add(OLinks.relatedEntity("rel+method=" + cs.getMethod(), s.getName(), cs.getPath()));
			}
		}
		
		List<OLink> links = new ArrayList<OLink>();
		links.addAll(associatedLinks);
		links.addAll(transitionLinks);
		return OEntities.create(entity.getEntitySet(), entity.getEntityKey(), entity.getProperties(), links);
	}

	@Override
	public void mapClass(Class<?> clazz) {
		// we do not implement this part of HateoasContext
		assert(false);
	}

	@Override
	public LinkableInfo getLinkableInfo(String linkKey) {
		Transition transition = linkTransitionMap.get(linkKey);
		// no transition, must be a transition to self
		String fqPath = (transition != null ? statePathMap.get(transition.getTarget()) :  entityResourcePathMap.get(linkKey));
		// there should not be any way to define linkKey's without defining a transition and resource
		assert(fqPath != null);
		ResourceInteractionModel rim = rimMap.get(fqPath);
		assert(rim != null);
		
		// TODO need to lookup from link registry, mock up GET link for now
		String label = "lookup label from EDMX";  // TODO get from entityDataServices
		String description = "lookup description from EDMX";  // TODO get from entityDataServices, in Accept-Language
		String method = (transition != null ? transition.getCommand().getMethod() : "GET");
		LinkableInfo link = new LinkableInfo(linkKey, rim.getFQResourcePath(), method, null, null, label, description, null);
		
		assert(link != null);
		return link;
	}

}

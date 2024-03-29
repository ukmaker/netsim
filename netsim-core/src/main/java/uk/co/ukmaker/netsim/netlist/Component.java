package uk.co.ukmaker.netsim.netlist;

import java.util.Collection;



/**
 * A Component is either a Device (a placeholder for a Model) or a user-defined Circuit
 * 
 * @author duncan
 *
 */

public interface Component {
	
	public String getName();
	public void setName(String name);
	public String getPath();
	public Collection<Terminal> getTerminals();
	public Terminal getTerminal(String name) throws Exception;
	public void addTerminal(Terminal t);
	public void setParentComponent(Component parent);
	public Collection<Component> getComponents();
}

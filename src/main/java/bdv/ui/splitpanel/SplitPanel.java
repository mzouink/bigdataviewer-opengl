/*-
 * #%L
 * BigDataViewer core classes with minimal dependencies.
 * %%
 * Copyright (C) 2012 - 2021 BigDataViewer developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.ui.splitpanel;

import bdv.ui.CardPanel;
import bdv.viewer.ViewerPanel;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * A {@code JSplitPane} with a {@code ViewerPanel} on the left and a
 * {@code CardPanel} on the right. Animated arrows are added to the
 * {@code ViewerPanel}, such that the right ({@code CardPanel}) pane can be
 * fully collapsed or exanded. The {@code CardPanel} can be also
 * programmatically collapsed or exanded using {@link #(boolean)}.
 *
 * @author Tim-Oliver Buchholz
 * @author Tobias Pietzsch
 */
public class SplitPanel extends JSplitPane
{
	private static final int DEFAULT_DIVIDER_SIZE = 3;

	private int width;

	public SplitPanel( final ViewerPanel viewerPanel, final CardPanel cardPanel )
	{
		super( JSplitPane.HORIZONTAL_SPLIT );

		configureSplitPane();

		final JComponent cardPanelComponent = cardPanel.getComponent();

		setLeftComponent( viewerPanel );
		setRightComponent( null );
		setBorder( null );
		setPreferredSize( viewerPanel.getPreferredSize() );

		super.setDividerSize( 0 );

		setDividerSize( DEFAULT_DIVIDER_SIZE );

		addComponentListener( new ComponentAdapter()
		{
			@Override
			public void componentResized( final ComponentEvent e )
			{
				final int w = getWidth();
				if ( width > 0 )
				{
					final int dl = getLastDividerLocation() + w - width;
					setLastDividerLocation( Math.max( 50, dl ) );
				}
				else
				{
					// When the component is first made visible, set LastDividerLocation to a reasonable value
					setDividerLocation( w );
					setLastDividerLocation( Math.max( w / 2, w - Math.max( 200, cardPanelComponent.getPreferredSize().width ) ) );
				}
				width = w;
			}
		} );
	}

	private void configureSplitPane()
	{
		this.setUI( new BasicSplitPaneUI()
		{
			@Override
			public BasicSplitPaneDivider createDefaultDivider()
			{
				return new BasicSplitPaneDivider( this )
				{
					private static final long serialVersionUID = 1L;

					@Override
					public void paint( final Graphics g )
					{
						g.setColor( Color.white );
						g.fillRect( 0, 0, getSize().width, getSize().height );
						super.paint( g );
					}

					@Override
					public void setBorder( final Border border )
					{
						super.setBorder( null );
					}
				};
			}
		} );
		this.setForeground( Color.white );
		this.setBackground( Color.white );
		this.setResizeWeight( 1.0 );
		this.setContinuousLayout( true );
	}

	// divider size set externally
	private int dividerSizeWhenVisible = DEFAULT_DIVIDER_SIZE;

	@Override
	public void setDividerSize( final int newSize )
	{
		dividerSizeWhenVisible = newSize;
	}


}

"""Browser input facts for the gesture fixtures; never alter player handlers."""
def observe_bar_pointer(pg):
    pg.evaluate('''() => {
      const h = window.__evpRoot.querySelector('.vbarhit');
      window.__barPointer = null;
      for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel'])
        h.addEventListener(type, e => {
          if (type === 'pointerdown' || window.__barPointer && e.pointerId === window.__barPointer.id)
            window.__barPointer = {id:e.pointerId, x:e.clientX, y:e.clientY, type};
        }, true);
    }''')

def lose_bar_pointer(pg, wrong=False):
    return pg.evaluate('''wrong => {
      const h=window.__evpRoot.querySelector('.vbarhit'), p=window.__barPointer;
      if (!p || !Number.isInteger(p.id)) throw Error('No real bar pointerdown');
      const id=wrong ? p.id + 100 : p.id;
      if (!wrong) { try { h.releasePointerCapture(id); } catch(e) {} }
      h.dispatchEvent(new PointerEvent('lostpointercapture',{pointerId:id,bubbles:true}));
      return p;
    }''', wrong)

def bounded_mouse(pg):
    """A finger stays within the viewport; missing OS events are tested explicitly."""
    width, height = pg.evaluate('() => [innerWidth,innerHeight]')
    def move(x, y, steps=1):
        pg.mouse.move(max(1,min(width-1,x)),max(1,min(height-1,y)),steps=steps)
    return move

package com.codecommit.cccp
package agent

import org.specs2.mutable.Specification

object ClientStateSpecs extends Specification {
  
  "client state" should {
    "send client ops immediately when synchronized" in {
      val state = Synchronized(0)
      state.applyClient(Op(0).chars("test")) must beLike {
        case Send(op, _) => {
          op.parent mustEqual 0
          op.version mustEqual 1
          
          op.delta.size mustEqual 1
          op.delta.getCharactersString(0) mustEqual "test"
        }
      }
    }
    
    "apply server ops immediately when synchronized" in {
      val state = Synchronized(0)
      state.applyServer(Op(0).chars("test")) must beLike {
        case Apply(op, _) => {
          op.parent mustEqual 0
          op.version mustEqual 1
          
          op.delta.size mustEqual 1
          op.delta.getCharactersString(0) mustEqual "test"
        }
      }
    }
    
    "buffer client ops when return is outstanding" in {
      var state: ClientState = Synchronized(0)
      val op = Op(0).chars("test")
      
      state.applyClient(op) must beLike {
        case Send(`op`, state2 @ AwaitingConfirm(`op`, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(4).chars("ing")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(7).chars("!")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
    }
    
    "flush client buffer on return" in {
      var state: ClientState = Synchronized(0)
      val op = Op(0).chars("test")
      
      state.applyClient(op) must beLike {
        case Send(`op`, state2 @ AwaitingConfirm(`op`, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(4).chars("ing")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(7).chars("!")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
      
      var sent: Op = null
      state.applyServer(op) must beLike {
        case Send(op, state2 @ AwaitingConfirm(op2, 1)) if op == op2 => {
          state = state2
          
          sent = op
          op.parent mustEqual 1
          op.version mustEqual 3
          
          op.delta.size mustEqual 2
          op.delta.getRetainItemCount(0) mustEqual 4
          op.delta.getCharactersString(1) mustEqual "ing!"
        }
      }
      
      state.applyClient(Op(0).retain(8).chars(" 1 2 3")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(op2, _, 1)) if op2 == sent => {
          state = state2
          ok
        }
      }
      
      state.applyServer(sent) must beLike {
        case Send(op, state2 @ AwaitingConfirm(op2, 3)) if op == op2 => {
          state = state2
          sent = op
          
          op.parent mustEqual 3
          op.version mustEqual 4
          
          op.delta.size mustEqual 2
          op.delta.getRetainItemCount(0) mustEqual 8
          op.delta.getCharactersString(1) mustEqual " 1 2 3"
        }
      }
      
      state.applyServer(sent) must beLike {
        case Shift(state2 @ Synchronized(4)) => {
          state = state2
          ok
        }
      }
    }
    
    "transform client buffer on server op" in {
      var state: ClientState = Synchronized(0)
      val op = Op(0).chars("test")
      
      state.applyClient(op) must beLike {
        case Send(_, state2 @ AwaitingConfirm(`op`, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(4).chars("ing")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyClient(Op(0).retain(7).chars("!")) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(`op`, _, 0)) => {
          state = state2
          ok
        }
      }
      
      state.applyServer(Op(0).chars(" isn't that swell?")) must beLike {
        case Apply(serverOp, state2 @ AwaitingWithBuffer(op2, buffer, 1)) => {
          state = state2
          
          serverOp.parent mustEqual 0
          serverOp.version mustEqual 1
          
          serverOp.delta.size mustEqual 2
          serverOp.delta.getRetainItemCount(0) mustEqual 8
          serverOp.delta.getCharactersString(1) mustEqual " isn't that swell?"
          
          op2.parent mustEqual 1
          op2.version mustEqual 2
          
          op2.delta.size mustEqual 2
          op2.delta.getCharactersString(0) mustEqual "test"
          op2.delta.getRetainItemCount(1) mustEqual 18
          
          buffer.parent mustEqual 2
          buffer.version mustEqual 4
          
          buffer.delta.size mustEqual 3
          buffer.delta.getRetainItemCount(0) mustEqual 4
          buffer.delta.getCharactersString(1) mustEqual "ing!"
          op2.delta.getRetainItemCount(1) mustEqual 18
        }
      }
    }
    
    "update version of second operation from non-initial synchronization" in {
      var state: ClientState = Synchronized(31)
      var sent: Op = null
      
      state.applyClient(Op(0).retain(467).chars("\n").retain(1536)) must beLike {
        case Send(op, state2 @ AwaitingConfirm(op2, 31)) => {
          state = state2
          sent = op
          
          op.parent mustEqual 31
          op.version mustEqual 32
          
          op2.parent mustEqual 31
          op.version mustEqual 32
        }
      }
      
      state.applyClient(Op(0).retain(468).chars("  ").retain(1536)) must beLike {
        case Shift(state2 @ AwaitingWithBuffer(outstanding, buffer, 31)) => {
          state = state2
          
          outstanding.parent mustEqual 31
          outstanding.version mustEqual 32
          
          buffer.parent mustEqual 32
          buffer.version mustEqual 33
        }
      }
      
      state.applyServer(sent) must beLike {
        case Send(op, state2 @ AwaitingConfirm(op2, 32)) => {
          state = state2
          sent = op
          
          op.parent mustEqual 32
          op.version mustEqual 33
          
          op2.parent mustEqual 32
          op2.version mustEqual 33
        }
      }
      
      state.applyServer(sent) must beLike {
        case Shift(state2 @ Synchronized(33)) => {
          state = state2
          sent = null
          ok
        }
      }
    }
  }
}

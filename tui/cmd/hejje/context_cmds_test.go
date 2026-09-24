package main

import (
	"reflect"
	"testing"
)

func TestParseFilter(t *testing.T) {
	f, err := parseFilter("rsRating:gte:80")
	if err != nil || f.Field != "rsRating" || f.Op != "gte" || f.Value != 80.0 {
		t.Fatalf("numeric filter: %+v %v", f, err)
	}
	f, _ = parseFilter("baseStatus:in:IN_BUY_ZONE,NEAR_PIVOT")
	if !reflect.DeepEqual(f.Value, []string{"IN_BUY_ZONE", "NEAR_PIVOT"}) {
		t.Fatalf("in filter: %+v", f)
	}
	f, _ = parseFilter("groupId:eq:information-technology")
	if f.Value != "information-technology" {
		t.Fatalf("text filter: %+v", f)
	}
	if _, err := parseFilter("rsRating>80"); err == nil {
		t.Fatal("a malformed filter must be refused")
	}
}

package subscription

import (
	"reflect"
	"testing"
)

func TestDeadlineDayList(t *testing.T) {
	s := Subscription{DeadlineDays: "1, 3,0,3"}
	if got := s.DeadlineDayList(); !reflect.DeepEqual(got, []int{3, 1, 0}) {
		t.Fatalf("got %v", got)
	}
}

func TestValidate(t *testing.T) {
	bad := "https://evil.example/x"
	if err := (Request{SlackWebhookURL: &bad}).Validate(); err == nil {
		t.Fatal("잘못된 웹훅 주소를 통과시킴")
	}
	good := "https://hooks.slack.com/services/x"
	days := "3,1,0"
	if err := (Request{SlackWebhookURL: &good, DeadlineDays: &days}).Validate(); err != nil {
		t.Fatal(err)
	}
	badDays := "3,a"
	if err := (Request{DeadlineDays: &badDays}).Validate(); err == nil {
		t.Fatal("잘못된 마감 일수를 통과시킴")
	}
}
